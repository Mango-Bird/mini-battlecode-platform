package dev.battlecode.server;

import dev.battlecode.competition.RoomDetails;
import dev.battlecode.network.*;
import dev.battlecode.protocol.*;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import java.io.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/** One demand at a time; business calls run on virtual tasks, never on a Jetty selector thread. */
public final class WebSocketConnection implements Session.Listener, ConnectionHandler, WebRoomConnection {
    private record Pending(String text, CompletableFuture<Void> flushed) { }
    private final UUID id = UUID.randomUUID();
    private final AtomicReference<ConnectionState> state = new AtomicReference<>(ConnectionState.CONNECTED);
    private final ReentrantLock dispatchLock = new ReentrantLock();
    private final WebSocketGateway gateway;
    private final RequestDispatcher dispatcher;
    private final ConnectionManager manager;
    private final NetworkConfig network;
    private final WebSocketConfig config;
    private final WebJsonCodec codec = new WebJsonCodec();
    private final ArrayBlockingQueue<Pending> outbound;
    private final long acceptedAt = System.nanoTime();
    private volatile long lastRequest = acceptedAt, writeStarted;
    private volatile Session socket;
    private volatile Thread writer;
    private volatile Pending writing;
    private volatile String identity = "";
    private int requests;

    WebSocketConnection(WebSocketGateway gateway, RequestDispatcher dispatcher, ConnectionManager manager,
                        NetworkConfig network, WebSocketConfig config) {
        this.gateway = gateway; this.dispatcher = dispatcher; this.manager = manager;
        this.network = network; this.config = config; outbound = new ArrayBlockingQueue<>(config.outboundCapacity());
    }
    public UUID connectionId() { return id; }
    public ConnectionState state() { return state.get(); }

    @Override public void onWebSocketOpen(Session session) {
        socket = session;
        session.setMaxTextMessageSize(WebJsonCodec.LIMIT);
        session.setMaxBinaryMessageSize(0);
        session.setMaxOutgoingFrames(2);
        session.setIdleTimeout(Duration.ofMillis(network.idleTimeoutMillis()));
        if (!gateway.admit(this)) { close(); return; }
        gateway.execute(this::writeLoop);
        session.demand();
    }
    @Override public void onWebSocketText(String text) {
        // Explicit demand limits inbound application tasks to one per admitted connection.
        gateway.execute(() -> handle(text));
    }
    @Override public void onWebSocketBinary(ByteBuffer payload, Callback callback) { callback.succeed(); close(); }
    @Override public void onWebSocketPing(ByteBuffer payload) { if (state.get() != ConnectionState.CLOSED) socket.demand(); }
    @Override public void onWebSocketPong(ByteBuffer payload) { if (state.get() != ConnectionState.CLOSED) socket.demand(); }
    @Override public void onWebSocketClose(int code, String reason, Callback callback) { close(); callback.succeed(); }
    @Override public void onWebSocketError(Throwable failure) { close(); }

    private void handle(String text) {
        try {
            Message request = codec.request(text, identity);
            Message response;
            dispatchLock.lock();
            try {
                if (state.get() == ConnectionState.CLOSING || state.get() == ConnectionState.CLOSED) return;
                response = dispatcher.dispatch(id, request);
                if (request.command() == Command.HELLO && response.command() == Command.ACK) {
                    identity = response.sessionId(); state.set(ConnectionState.ACTIVE);
                }
            } finally { dispatchLock.unlock(); }
            lastRequest = System.nanoTime();
            enqueue(codec.message(response)).get(config.writeTimeoutMillis(), TimeUnit.MILLISECONDS);
            if (request.command() == Command.HELLO && response.command() == Command.ACK)
                roomState(UUID.fromString(identity), null, 0, null);
            if (++requests >= 10_000 || fatal(response) || request.command() == Command.QUIT && response.command() == Command.ACK) {
                socket.close(1000, "Session ended", Callback.from(this::close, ignored -> close())); return;
            }
            if (state.get() == ConnectionState.ACTIVE || state.get() == ConnectionState.CONNECTED) socket.demand();
        } catch (IOException | ExecutionException | TimeoutException | RuntimeException ex) { close(); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); close(); }
    }
    private boolean fatal(Message response) {
        return response.command() == Command.ERROR && Set.of(ErrorCode.HANDSHAKE_REQUIRED,
                ErrorCode.INVALID_SESSION, ErrorCode.UNSUPPORTED_VERSION).contains(response.errorCode());
    }
    public void send(Message message) throws IOException { enqueue(codec.message(message)); }
    public void roomState(UUID session, UUID roomId, long revision, RoomDetails details) throws IOException {
        enqueue(codec.room(session, roomId, revision, details));
    }
    private CompletableFuture<Void> enqueue(String text) throws IOException {
        var pending = new Pending(text, new CompletableFuture<>());
        if (state.get() == ConnectionState.CLOSING || state.get() == ConnectionState.CLOSED) throw new IOException("Connection closed");
        if (!outbound.offer(pending)) { close(); throw new IOException("Web outbound queue full"); }
        if ((state.get() == ConnectionState.CLOSING || state.get() == ConnectionState.CLOSED) && outbound.remove(pending))
            pending.flushed().completeExceptionally(new IOException("Connection closed"));
        return pending.flushed();
    }
    private void writeLoop() {
        writer = Thread.currentThread();
        try {
            while (state.get() != ConnectionState.CLOSED && state.get() != ConnectionState.CLOSING) {
                Pending pending = outbound.take(); writing = pending; writeStarted = System.nanoTime();
                socket.sendText(pending.text(), Callback.from(() -> pending.flushed().complete(null), pending.flushed()::completeExceptionally));
                pending.flushed().get(config.writeTimeoutMillis(), TimeUnit.MILLISECONDS);
                writing = null; writeStarted = 0;
            }
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        catch (ExecutionException | TimeoutException | RuntimeException ex) { /* Cleanup below. */ }
        finally { close(); }
    }
    void checkDeadline(long now) {
        if (state.get() == ConnectionState.CONNECTED && now - acceptedAt >= TimeUnit.MILLISECONDS.toNanos(network.handshakeTimeoutMillis())
                || now - lastRequest >= TimeUnit.MILLISECONDS.toNanos(network.idleTimeoutMillis())
                || writeStarted != 0 && now - writeStarted >= TimeUnit.MILLISECONDS.toNanos(config.writeTimeoutMillis())) close();
    }
    public void close() {
        ConnectionState previous = state.getAndUpdate(value -> value == ConnectionState.CLOSED || value == ConnectionState.CLOSING ? value : ConnectionState.CLOSING);
        if (previous == ConnectionState.CLOSING || previous == ConnectionState.CLOSED) return;
        if (socket != null) try { socket.disconnect(); } catch (RuntimeException ignored) { /* Still release application resources. */ }
        var failure = new IOException("WebSocket closed");
        Pending current = writing; if (current != null) current.flushed().completeExceptionally(failure);
        Pending pending; while ((pending = outbound.poll()) != null) pending.flushed().completeExceptionally(failure);
        Thread currentWriter = writer; if (currentWriter != null) currentWriter.interrupt();
        // Never run potentially contended application cleanup on Jetty I/O threads.
        gateway.execute(() -> {
            dispatchLock.lock();
            try { dispatcher.disconnected(id); }
            finally { manager.remove(id); gateway.remove(this); state.set(ConnectionState.CLOSED); dispatchLock.unlock(); }
        });
    }
}
