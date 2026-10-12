package dev.battlecode.client;

import dev.battlecode.protocol.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.ReentrantLock;

/** One-shot client. A background reader correlates responses and separates unsolicited events. */
public final class DefaultTcpClient implements TcpClient {
    private final MessageCodec codec = new LengthPrefixedMessageCodec();
    private record Awaiting(Command command, CompletableFuture<Message> future) { }
    private final Map<String, Awaiting> pending = new ConcurrentHashMap<>();
    private final ArrayBlockingQueue<Message> events = new ArrayBlockingQueue<>(256);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong sequence = new AtomicLong();
    private final ReentrantLock requestLock = new ReentrantLock();
    private final ReentrantLock connectLock = new ReentrantLock();
    private final ExecutorService workers = Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("battlecode-client-", 0).factory());
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().daemon(true).name("battlecode-client-deadlines").factory());
    private final int connectTimeoutMillis;
    private final int responseTimeoutMillis;
    private volatile Socket socket;
    private volatile String sessionId = "";
    private boolean started;

    public DefaultTcpClient() { this(5_000, 10_000); }

    /** Timeouts can be shortened in tests; production defaults follow protocol 1.0. */
    public DefaultTcpClient(int connectTimeoutMillis, int responseTimeoutMillis) {
        if (connectTimeoutMillis < 1 || responseTimeoutMillis < 1) throw new IllegalArgumentException("Invalid timeouts");
        this.connectTimeoutMillis = connectTimeoutMillis; this.responseTimeoutMillis = responseTimeoutMillis;
    }

    public void connect(String host, int port) throws IOException {
        connectLock.lock();
        try {
            if (started || closed.get()) throw new IllegalStateException("Client is one-shot");
            started = true;
            var transport = new Socket();
            socket = transport;
            try {
                transport.connect(new InetSocketAddress(host, port), connectTimeoutMillis);
                transport.setTcpNoDelay(true);
                transport.setSoTimeout(60_000);
                if (closed.get()) throw new IOException("Client closed during connect");
                workers.execute(this::readLoop);
                workers.execute(this::heartbeatLoop);
            } catch (IOException | RuntimeException ex) {
                // close can race before socket publication; always close this local transport too.
                try { transport.close(); } catch (IOException cleanup) { ex.addSuppressed(cleanup); }
                close(); throw ex;
            }
        } finally { connectLock.unlock(); }
    }

    public Message hello(String name) throws IOException { return request(Command.HELLO, Map.of("name", name)); }
    public Message request(Command command, Map<String,String> payload) throws IOException {
        return request(new Message(Message.VERSION, "c" + sequence.incrementAndGet(),
            command == Command.HELLO && sessionId.isEmpty() ? "" : sessionId, command, ErrorCode.NONE, payload));
    }

    public Message request(Message message) throws IOException {
        requestLock.lock();
        try {
            if (closed.get() || socket == null || !socket.isConnected()) throw new IOException("Client not connected");
            var reply = new CompletableFuture<Message>();
            if (pending.putIfAbsent(message.requestId(), new Awaiting(message.command(), reply)) != null) throw new IOException("Request already pending");
            ScheduledFuture<?> deadline;
            try {
                // The timer also aborts a blocked socket write, not only a response wait.
                deadline = deadlines.schedule(() -> failAndClose(new SocketTimeoutException("Response deadline exceeded")),
                    responseTimeoutMillis, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException ex) {
                pending.remove(message.requestId()); throw new IOException("Client closed", ex);
            }
            try {
                codec.write(socket.getOutputStream(), message);
                Message response = reply.get(responseTimeoutMillis, TimeUnit.MILLISECONDS);
                if (message.command() == Command.QUIT && response.command() == Command.ACK) close();
                return response;
            } catch (ExecutionException ex) {
                close(); throw new IOException("Connection ended before response", ex.getCause());
            } catch (TimeoutException ex) {
                close(); throw new SocketTimeoutException("Response deadline exceeded");
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt(); close(); throw new IOException("Request interrupted", ex);
            } catch (IOException ex) { close(); throw ex; }
            finally { deadline.cancel(false); pending.remove(message.requestId()); }
        } finally { requestLock.unlock(); }
    }

    private void readLoop() {
        try {
            while (!closed.get()) {
                var message = codec.read(socket.getInputStream());
                if (message.isEmpty()) break;
                Message response = message.get();
                if (!Message.VERSION.equals(response.version())) throw new IOException("Unsupported server version");
                if (response.command() == Command.ROOM_EVENT || response.command() == Command.MATCH_EVENT) {
                    if (!response.requestId().isEmpty() || sessionId.isEmpty() || !sessionId.equals(response.sessionId())
                        || !events.offer(response)) throw new IOException("Invalid event or event queue full");
                } else if (response.command() == Command.ACK || response.command() == Command.ERROR) {
                    var waiting = pending.get(response.requestId());
                    if (waiting == null) throw new IOException("Uncorrelated response");
                    if (!sessionId.isEmpty() && !sessionId.equals(response.sessionId())) throw new IOException("Invalid response session");
                    if (response.command() == Command.ACK) {
                        if (!waiting.command().name().equals(response.payload().get("for"))) throw new IOException("ACK command mismatch");
                        if (waiting.command() == Command.HELLO) {
                            if (!canonicalUuid(response.sessionId())) throw new IOException("Invalid HELLO session");
                            sessionId = response.sessionId();
                        }
                    }
                    if (!waiting.future().complete(response)) throw new IOException("Duplicate response");
                } else throw new IOException("Unexpected server command");
            }
            failAndClose(new EOFException("Server disconnected"));
        } catch (IOException ex) { failAndClose(ex); }
    }

    private void heartbeatLoop() {
        try {
            while (!closed.get()) {
                Thread.sleep(20_000);
                if (!closed.get() && !sessionId.isEmpty()) request(Command.PING, Map.of());
            }
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        catch (IOException ex) { failAndClose(ex); }
    }

    private boolean canonicalUuid(String value) {
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException ex) { return false; }
    }
    public String sessionId() { return sessionId; }
    public Optional<Message> pollEvent() { return Optional.ofNullable(events.poll()); }
    public Optional<Message> pollEvent(long timeout, TimeUnit unit) throws InterruptedException {
        return Optional.ofNullable(events.poll(timeout, unit));
    }
    public boolean isClosed() { return closed.get(); }
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long end = System.nanoTime() + unit.toNanos(timeout);
        return workers.awaitTermination(Math.max(0, end - System.nanoTime()), TimeUnit.NANOSECONDS)
            && deadlines.awaitTermination(Math.max(0, end - System.nanoTime()), TimeUnit.NANOSECONDS);
    }

    private void failAndClose(IOException failure) {
        pending.values().forEach(waiting -> waiting.future().completeExceptionally(failure));
        try { close(); } catch (IOException ignored) { }
    }

    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) return;
        try { if (socket != null) socket.close(); }
        finally {
            pending.values().forEach(waiting -> waiting.future().completeExceptionally(new IOException("Client closed")));
            deadlines.shutdownNow(); workers.shutdownNow();
        }
    }
}
