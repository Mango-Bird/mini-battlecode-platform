package dev.battlecode.client;

import dev.battlecode.protocol.Command;
import dev.battlecode.protocol.ErrorCode;
import dev.battlecode.protocol.Message;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/** One independently connected TCP bot. The bot runs wholly in the client process. */
public final class BotClient implements AutoCloseable {
    private static final Duration EVENT_POLL = Duration.ofMillis(250);
    private final DefaultTcpClient transport;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final LongAdder successfulRequests = new LongAdder();
    private final LongAdder failedRequests = new LongAdder();
    private volatile String roomId;
    private volatile String matchId;
    private volatile String ruleId;

    public BotClient() { this(new DefaultTcpClient()); }
    public BotClient(DefaultTcpClient transport) { this.transport = Objects.requireNonNull(transport); }

    public void connect(String host, int port, String name) throws IOException {
        transport.connect(host, port);
        checked(requestHello(name), "HELLO");
    }

    public String createRoom(String ruleId, int maxTurns, int turnTimeoutMillis, Map<String, String> options) throws IOException {
        var payload = new HashMap<String, String>();
        payload.put("ruleId", ruleId); payload.put("maxPlayers", "2");
        payload.put("maxTurns", Integer.toString(maxTurns)); payload.put("turnTimeoutMillis", Integer.toString(turnTimeoutMillis));
        options.forEach((key, value) -> payload.put("option." + key, value));
        Message response = checked(request(Command.CREATE_ROOM, payload), "CREATE_ROOM");
        this.roomId = required(response, "roomId"); this.ruleId = ruleId;
        return roomId;
    }

    public void joinRoom(String roomId, String ruleId) throws IOException {
        Message response = checked(request(Command.JOIN_ROOM, Map.of("roomId", roomId)), "JOIN_ROOM");
        this.roomId = roomId; this.ruleId = Objects.requireNonNull(ruleId);
    }

    public String startMatch() throws IOException {
        if (roomId == null) throw new IllegalStateException("Bot has no room");
        Message response = checked(request(Command.START_MATCH, Map.of("roomId", roomId)), "START_MATCH");
        matchId = required(response, "matchId"); return matchId;
    }

    public String sessionId() { return transport.sessionId(); }
    public long successfulRequests() { return successfulRequests.sum(); }
    public long failedRequests() { return failedRequests.sum(); }

    /** Waits for this bot's match to end; does not spin when no event is available. */
    public MatchOutcome playUntilTerminal(BotStrategy strategy, Duration timeout) throws IOException, InterruptedException {
        Objects.requireNonNull(strategy); Objects.requireNonNull(timeout);
        if (roomId == null || ruleId == null || timeout.isZero() || timeout.isNegative()) throw new IllegalStateException("Bot is not ready or timeout is invalid");
        long deadline = System.nanoTime() + timeout.toNanos();
        Set<String> submitted = new HashSet<>();
        while (!closed.get() && System.nanoTime() < deadline) {
            long remaining = deadline - System.nanoTime();
            Optional<Message> event = transport.pollEvent(Math.min(EVENT_POLL.toNanos(), remaining), TimeUnit.NANOSECONDS);
            if (event.isEmpty()) {
                if (transport.isClosed()) throw new IOException("Bot connection ended before terminal match event");
                continue;
            }
            Message message = event.get();
            if (message.command() != Command.MATCH_EVENT) continue;
            String type = message.payload().get("type"), eventMatch = message.payload().get("matchId");
            if (type == null) throw new IOException("MATCH_EVENT missing type");
            if (matchId == null && Set.of("STARTED", "STATE").contains(type)) matchId = eventMatch;
            if (matchId == null || !matchId.equals(eventMatch)) continue;
            if (type.equals("FINISHED") || type.equals("CANCELLED"))
                return new MatchOutcome(matchId, type, message.payload().getOrDefault("winners", ""), message.payload().getOrDefault("reason", ""));
            if (!type.equals("STARTED") && !type.equals("STATE")) continue;
            String turn = message.payload().get("turn");
            int turnNumber;
            try { turnNumber = Integer.parseInt(turn); }
            catch (RuntimeException ex) { throw new IOException("Invalid turn in MATCH_EVENT", ex); }
            var view = new HashMap<String, String>();
            message.payload().forEach((key, value) -> { if (key.startsWith("view.")) view.put(key.substring(5), value); });
            BotMatchContext context = new BotMatchContext(ruleId, sessionId(), matchId, turnNumber, view);
            String submissionKey = matchId + ":" + turnNumber;
            if (submitted.contains(submissionKey)) continue;
            Map<String, String> action = Objects.requireNonNull(strategy.chooseAction(context), "strategy returned null");
            if (action.isEmpty()) continue;
            var payload = new HashMap<String, String>(); payload.put("matchId", matchId); payload.put("turn", turn);
            action.forEach((key, value) -> payload.put("action." + key, value));
            Message actionResponse = request(Command.ACTION, payload);
            if (actionResponse.command() == Command.ERROR && actionResponse.errorCode() == ErrorCode.INVALID_STATE) {
                // A terminal transition (for example, a peer disconnect) may be queued behind
                // STARTED/STATE. The server is authoritative; keep draining events for terminal.
                failedRequests.increment();
                continue;
            }
            checked(actionResponse, "ACTION"); submitted.add(submissionKey);
        }
        throw new java.net.SocketTimeoutException("Bot match deadline exceeded");
    }

    @Override public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) return;
        if (!transport.isClosed()) {
            try { transport.request(Command.QUIT, Map.of()); }
            catch (IOException ignored) { }
            finally { transport.close(); }
        }
    }

    public boolean awaitTermination(Duration timeout) throws InterruptedException {
        return transport.awaitTermination(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    private static String required(Message message, String key) throws IOException {
        String value = message.payload().get(key);
        if (value == null || value.isBlank()) throw new IOException("ACK missing " + key);
        return value;
    }
    private static void requireAck(Message message, String command) throws IOException {
        if (message.command() != Command.ACK) throw new IOException(command + " rejected: " + message.errorCode() + " " + message.payload().getOrDefault("message", ""));
    }

    private Message checked(Message message, String command) throws IOException {
        try { requireAck(message, command); successfulRequests.increment(); return message; }
        catch (IOException ex) { failedRequests.increment(); throw ex; }
    }

    private Message requestHello(String name) throws IOException {
        try { return transport.hello(name); }
        catch (IOException ex) { failedRequests.increment(); throw ex; }
    }
    private Message request(Command command, Map<String, String> payload) throws IOException {
        try { return transport.request(command, payload); }
        catch (IOException ex) { failedRequests.increment(); throw ex; }
    }

    public record MatchOutcome(String matchId, String status, String winners, String reason) { }
}
