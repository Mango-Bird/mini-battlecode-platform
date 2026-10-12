package dev.battlecode.network;

import dev.battlecode.protocol.*;
import java.io.IOException;
import java.net.*;
import java.util.Objects;
import java.util.concurrent.*;

/** At most maxConnections admissions, two virtual tasks per connection and one accept task. */
public final class DefaultTcpServer implements TcpServer {
    private final NetworkConfig config;
    private final RequestDispatcher dispatcher;
    private final MessageCodec codec = new LengthPrefixedMessageCodec();
    private final DefaultConnectionManager manager;
    private final ExecutorService tasks = Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("battlecode-network-", 0).factory());
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().daemon(true).name("battlecode-write-watchdog").factory());
    private volatile ServerSocket listener;
    private volatile boolean closed;
    private boolean started;

    public DefaultTcpServer(NetworkConfig config, RequestDispatcher dispatcher) {
        this(config, dispatcher, new DefaultConnectionManager(config.maxConnections()));
    }

    /** Allows the composition root to route notifications through the existing ConnectionManager contract. */
    public DefaultTcpServer(NetworkConfig config, RequestDispatcher dispatcher, DefaultConnectionManager manager) {
        this.config = Objects.requireNonNull(config);
        this.dispatcher = Objects.requireNonNull(dispatcher);
        this.manager = Objects.requireNonNull(manager);
    }

    public synchronized void start() throws IOException {
        if (closed || started) throw new IllegalStateException("Server is one-shot; already started/closed");
        var socket = new ServerSocket();
        try {
            socket.bind(new InetSocketAddress(config.bindAddress(), config.port()), config.maxConnections());
            listener = socket; started = true;
            watchdog.scheduleAtFixedRate(() -> {
                long now = System.nanoTime();
                for (var connection : manager.snapshot())
                    if (connection instanceof SocketConnectionHandler tcp) tcp.checkWriteDeadline(now);
            }, 50, 50, TimeUnit.MILLISECONDS);
            tasks.execute(this::acceptLoop);
        } catch (IOException | RuntimeException ex) {
            socket.close(); close(); throw ex;
        }
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket socket = listener.accept();
                long acceptedAt = System.nanoTime();
                SocketConnectionHandler connection = null;
                try {
                    socket.setTcpNoDelay(true);
                    connection = new SocketConnectionHandler(socket, config, codec, dispatcher, manager, acceptedAt);
                    manager.register(connection);
                    var admitted = connection;
                    tasks.execute(admitted::writeLoop);
                    tasks.execute(admitted::readLoop);
                } catch (IOException | IllegalStateException | RejectedExecutionException ex) {
                    if (connection != null) connection.close(); else socket.close();
                }
            } catch (IOException ex) {
                if (!closed) System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING, "Accept failed", ex);
                break;
            }
        }
    }

    public int localPort() {
        var socket = listener;
        if (socket == null) throw new IllegalStateException("Server not started");
        return socket.getLocalPort();
    }

    public int connectionCount() { return manager.size(); }
    public boolean isTerminated() { return tasks.isTerminated() && watchdog.isTerminated(); }

    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        IOException failure = null;
        try { if (listener != null) listener.close(); } catch (IOException ex) { failure = ex; }
        manager.close();
        watchdog.shutdownNow(); tasks.shutdownNow();
        try {
            if (!tasks.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
                || !watchdog.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS))
                failure = new IOException("Server shutdown deadline exceeded");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); failure = new IOException("Shutdown interrupted", ex);
        }
        if (failure != null) throw failure;
    }
}
