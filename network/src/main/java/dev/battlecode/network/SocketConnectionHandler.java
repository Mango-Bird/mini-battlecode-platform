package dev.battlecode.network;

import dev.battlecode.protocol.*;
import java.io.*;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Transport only: session/envelope policy belongs to the injected dispatcher. */
final class SocketConnectionHandler implements ConnectionHandler {
    static final int OUTBOUND_CAPACITY = 256;
    private static final long WRITE_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10);
    private record Pending(Message message, CompletableFuture<Void> completion) { }
    private final UUID id = UUID.randomUUID();
    private final Socket socket;
    private final NetworkConfig config;
    private final MessageCodec codec;
    private final RequestDispatcher dispatcher;
    private final DefaultConnectionManager manager;
    private final ArrayBlockingQueue<Pending> outbound = new ArrayBlockingQueue<>(OUTBOUND_CAPACITY);
    private final AtomicReference<ConnectionState> state = new AtomicReference<>(ConnectionState.CONNECTED);
    private final Object dispatchLock = new Object();
    private final long handshakeDeadline;
    private volatile long writeStarted;
    private volatile Pending writing;
    private volatile Thread writer;

    SocketConnectionHandler(Socket socket, NetworkConfig config, MessageCodec codec,
                            RequestDispatcher dispatcher, DefaultConnectionManager manager, long acceptedAt) {
        this.socket = socket; this.config = config; this.codec = codec;
        this.dispatcher = dispatcher; this.manager = manager;
        handshakeDeadline = acceptedAt + TimeUnit.MILLISECONDS.toNanos(config.handshakeTimeoutMillis());
    }

    public UUID connectionId() { return id; }
    public ConnectionState state() { return state.get(); }

    public void send(Message message) throws IOException { enqueue(message); }

    private CompletableFuture<Void> enqueue(Message message) throws IOException {
        var completion = new CompletableFuture<Void>();
        var pending = new Pending(message, completion);
        if (state.get() == ConnectionState.CLOSED || state.get() == ConnectionState.CLOSING)
            throw new IOException("Connection closed");
        if (!outbound.offer(pending)) { close(); throw new IOException("Outbound queue full"); }
        // close may race with offer; never leave an uncompleted future in the queue.
        if ((state.get() == ConnectionState.CLOSED || state.get() == ConnectionState.CLOSING) && outbound.remove(pending))
            completion.completeExceptionally(new IOException("Connection closed"));
        return completion;
    }

    void writeLoop() {
        writer = Thread.currentThread();
        try {
            while (state.get() != ConnectionState.CLOSED) {
                Pending pending = outbound.take();
                writing = pending;
                writeStarted = System.nanoTime();
                codec.write(socket.getOutputStream(), pending.message());
                writeStarted = 0;
                pending.completion().complete(null);
                writing = null;
            }
        } catch (IOException | InterruptedException ex) {
            Pending current = writing;
            if (current != null) current.completion().completeExceptionally(ex);
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally { closeQuietly(); }
    }

    void readLoop() {
        long idleDeadline = handshakeDeadline;
        int requests = 0;
        try {
            while (state.get() != ConnectionState.CLOSED) {
                long deadline = state.get() == ConnectionState.CONNECTED ? handshakeDeadline : idleDeadline;
                var decoded = codec.read(new DeadlineInputStream(socket, deadline, config.frameTimeoutMillis()));
                if (decoded.isEmpty()) break;
                Message request = decoded.get();
                idleDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.idleTimeoutMillis());
                Message response;
                synchronized (dispatchLock) {
                    if (state.get() == ConnectionState.CLOSED || state.get() == ConnectionState.CLOSING) break;
                    response = dispatcher.dispatch(id, request);
                }
                if (System.nanoTime() >= deadline) break;
                boolean helloAccepted = request.command() == Command.HELLO && response.command() == Command.ACK;
                // Completion ensures QUIT/fatal errors are on the wire before closing the socket.
                enqueue(response).get(10, TimeUnit.SECONDS);
                if (helloAccepted) state.compareAndSet(ConnectionState.CONNECTED, ConnectionState.ACTIVE);
                if (++requests >= 10_000 || fatal(response)
                    || (request.command() == Command.QUIT && response.command() == Command.ACK)) break;
            }
        } catch (IOException | ExecutionException | TimeoutException ex) {
            // Uncorrelated/truncated/malformed transport: close without inventing a request ID.
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException ex) {
            System.getLogger(getClass().getName()).log(System.Logger.Level.ERROR, "Dispatcher failed", ex);
        } finally { closeQuietly(); }
    }

    private boolean fatal(Message response) {
        return response.command() == Command.ERROR && switch (response.errorCode()) {
            case UNSUPPORTED_VERSION, HANDSHAKE_REQUIRED, INVALID_SESSION -> true;
            default -> false;
        };
    }

    void checkWriteDeadline(long now) {
        long started = writeStarted;
        if (started != 0 && now - started >= WRITE_TIMEOUT_NANOS) closeQuietly();
    }

    public void close() throws IOException {
        ConnectionState previous = state.getAndUpdate(value ->
            value == ConnectionState.CLOSED || value == ConnectionState.CLOSING ? value : ConnectionState.CLOSING);
        if (previous == ConnectionState.CLOSED || previous == ConnectionState.CLOSING) return;
        try { socket.close(); }
        finally {
            var failure = new IOException("Connection closed");
            Pending current = writing;
            if (current != null) current.completion().completeExceptionally(failure);
            Pending pending;
            while ((pending = outbound.poll()) != null) pending.completion().completeExceptionally(failure);
            Thread task = writer;
            if (task != null && task != Thread.currentThread()) task.interrupt();
            try { synchronized (dispatchLock) { dispatcher.disconnected(id); } }
            catch (RuntimeException ex) {
                System.getLogger(getClass().getName()).log(System.Logger.Level.ERROR, "Disconnect callback failed", ex);
            }
            finally { manager.remove(id); state.set(ConnectionState.CLOSED); }
        }
    }

    private void closeQuietly() {
        try { close(); } catch (IOException ignored) { }
    }
}
