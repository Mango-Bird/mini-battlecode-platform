package dev.battlecode.server;

import dev.battlecode.network.*;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** HTTP/WebSocket transport only; all commands are delegated through the existing dispatcher. */
final class WebSocketGateway implements AutoCloseable {
    private final Server server;
    private final ServerConnector connector;
    private final ConnectionManager manager;
    private final Set<WebSocketConnection> connections = ConcurrentHashMap.newKeySet();
    private final ExecutorService tasks = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("battlecode-web-", 0).factory());
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).name("battlecode-web-watchdog").factory());
    private final Object admission = new Object();
    private boolean closed;

    WebSocketGateway(WebSocketConfig config, NetworkConfig network, ConnectionManager manager, RequestDispatcher dispatcher) {
        this.manager = manager;
        // Jetty dispatches internal continuation tasks; its queue must remain unbounded.
        // Admission is bounded at sockets and application connections instead.
        var pool = new QueuedThreadPool(32, 4, 60_000);
        pool.setName("battlecode-jetty"); server = new Server(pool); server.setStopTimeout(2000);
        connector = new ServerConnector(server, 1, 1); connector.setHost(config.bindAddress()); connector.setPort(config.port());
        connector.setAcceptQueueSize(64); connector.setIdleTimeout(network.handshakeTimeoutMillis()); server.addConnector(connector);
        server.addBean(new ConnectionLimit(network.maxConnections(), connector));
        var context = new ContextHandler("/"); server.setHandler(context);
        var upgrade = WebSocketUpgradeHandler.from(server, context, container -> {
            container.setMaxTextMessageSize(WebJsonCodec.LIMIT);
            container.setIdleTimeout(java.time.Duration.ofMillis(network.idleTimeoutMillis()));
            container.addMapping("/ws", (request, response, callback) -> {
                String origin = request.getHeaders().get("Origin");
                if (origin == null || !config.allowedOrigins().contains(origin)) {
                    Response.writeError(request, response, callback, 403, "Origin not allowed"); return null;
                }
                response.setExtensions(List.of()); // No permessage-deflate or inflation beyond the message budget.
                return new WebSocketConnection(this, dispatcher, manager, network, config);
            });
        });
        upgrade.setHandler(new Handler.Abstract() {
            public boolean handle(Request request, Response response, Callback callback) {
                if (!request.getMethod().equals("GET") || !Request.getPathInContext(request).equals("/health")) return false;
                response.setStatus(200); response.getHeaders().put("Content-Type", "application/json");
                response.getHeaders().put("Cache-Control", "no-store");
                Content.Sink.write(response, true, "{\"status\":\"ok\"}", callback);
                return true;
            }
        });
        context.setHandler(upgrade);
    }
    void start() throws IOException {
        try {
            server.start(); watchdog.scheduleAtFixedRate(() -> {
                long now = System.nanoTime(); connections.forEach(connection -> connection.checkDeadline(now));
            }, 20, 20, TimeUnit.MILLISECONDS);
        } catch (Exception ex) { close(); throw new IOException("WebSocket startup failed", ex); }
    }
    boolean admit(WebSocketConnection connection) {
        synchronized (admission) {
            if (closed) return false;
            try { manager.register(connection); connections.add(connection); return true; }
            catch (IllegalStateException ex) { return false; }
        }
    }
    void execute(Runnable task) {
        try { tasks.execute(task); }
        catch (RejectedExecutionException ex) { task.run(); /* Last idempotent cleanup after shutdown. */ }
    }
    void remove(WebSocketConnection connection) { connections.remove(connection); }
    int localPort() { return connector.getLocalPort(); }
    boolean isTerminated() { return server.isStopped() && tasks.isTerminated() && watchdog.isTerminated() && connections.isEmpty(); }
    public void close() throws IOException {
        List<WebSocketConnection> snapshot;
        synchronized (admission) { if (closed) return; closed = true; snapshot = List.copyOf(connections); }
        snapshot.forEach(WebSocketConnection::close); watchdog.shutdownNow();
        IOException failure = null;
        try { server.stop(); } catch (Exception ex) { failure = new IOException("Jetty shutdown failed", ex); }
        tasks.shutdown();
        try {
            if (!tasks.awaitTermination(5, TimeUnit.SECONDS) || !watchdog.awaitTermination(1, TimeUnit.SECONDS)) {
                tasks.shutdownNow(); throw new IOException("Web tasks shutdown deadline exceeded");
            }
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException("Web shutdown interrupted", ex); }
        if (failure != null) throw failure;
    }
}
