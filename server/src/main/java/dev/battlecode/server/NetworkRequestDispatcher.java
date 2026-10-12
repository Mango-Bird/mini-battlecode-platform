package dev.battlecode.server;

import dev.battlecode.network.RequestDispatcher;
import dev.battlecode.protocol.*;
import dev.battlecode.competition.*;
import dev.battlecode.competition.MatchException;
import dev.battlecode.rulesapi.*;
import dev.battlecode.engine.EngineException;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Connection-bound identity/protocol adapter; business mutations are delegated through Lobby. */
public final class NetworkRequestDispatcher implements RequestDispatcher {
    private static final int REQUEST_QUOTA = 10_000;
    private static final Set<Command> CLIENT_COMMANDS = EnumSet.range(Command.HELLO, Command.QUIT);
    private static final class Context {
        String sessionId = "";
        final Set<String> requests = new HashSet<>();
        int count;
        double tokens = 40;
        long refillAt = System.nanoTime();
        boolean closed;

        boolean permit() {
            long now = System.nanoTime();
            tokens = Math.min(40, tokens + (now - refillAt) / 1_000_000_000.0 * 20);
            refillAt = now;
            if (tokens < 1) return false;
            tokens--; return true;
        }
    }
    private final Map<UUID, Context> contexts = new ConcurrentHashMap<>();
    private final Lobby lobby;
    private final RuleRegistry registry;
    private final ConcurrentMap<UUID,UUID> routes;
    private final MatchManager matches;

    /** Transport-only harness retained for Phase 2 regression tests. ServerMain uses the composed room runtime. */
    public NetworkRequestDispatcher() { this(null, new InMemoryRuleRegistry(List.of()), new ConcurrentHashMap<>()); }
    public NetworkRequestDispatcher(Lobby lobby, RuleRegistry registry, ConcurrentMap<UUID,UUID> routes) {
        this(lobby, registry, routes, null);
    }
    public NetworkRequestDispatcher(Lobby lobby, RuleRegistry registry, ConcurrentMap<UUID,UUID> routes, MatchManager matches) {
        this.lobby = lobby; this.registry = Objects.requireNonNull(registry); this.routes = Objects.requireNonNull(routes);
        this.matches = matches;
    }

    public Message dispatch(UUID connectionId, Message request) {
        Context context = contexts.computeIfAbsent(connectionId, ignored -> new Context());
        synchronized (context) {
            if (context.closed) return error(request, context, ErrorCode.INVALID_SESSION, "Connection ended");
            // Count all decoded requests, including invalid ones, to bound lifetime work and ID memory.
            context.count++;
            try { return handle(connectionId, context, request); }
            catch (LobbyException ex) {
                ErrorCode code = switch (ex.reason()) {
                    case INVALID_SESSION -> ErrorCode.INVALID_SESSION;
                    case INVALID_STATE -> ErrorCode.INVALID_STATE;
                    case RULE_NOT_FOUND -> ErrorCode.RULE_NOT_FOUND;
                    case ROOM_NOT_FOUND -> ErrorCode.ROOM_NOT_FOUND;
                    case ROOM_FULL -> ErrorCode.ROOM_FULL;
                    case INVALID_CONFIG -> ErrorCode.INVALID_PAYLOAD;
                };
                return error(request, context, code, ex.getMessage());
            }
            catch (MatchException ex) {
                return error(request, context, switch(ex.reason()) {
                    case NOT_OWNER -> ErrorCode.NOT_OWNER;
                    case INVALID_STATE -> ErrorCode.INVALID_STATE;
                    case INTERNAL_ERROR -> ErrorCode.INTERNAL_ERROR;
                }, ex.getMessage());
            }
            catch (EngineException ex) {
                return error(request, context, switch(ex.reason()) {
                    case INVALID_STATE -> ErrorCode.INVALID_STATE;
                    case NOT_YOUR_TURN -> ErrorCode.NOT_YOUR_TURN;
                    case ACTION_REJECTED -> ErrorCode.ACTION_REJECTED;
                    case TIMEOUT -> ErrorCode.TIMEOUT;
                    case INTERNAL_ERROR -> ErrorCode.INTERNAL_ERROR;
                }, ex.getMessage());
            }
            catch (RuntimeException ex) {
                System.getLogger(getClass().getName()).log(System.Logger.Level.ERROR, "Application request failed", ex);
                return error(request, context, ErrorCode.INTERNAL_ERROR, "Internal application error");
            }
            finally { if (context.count >= REQUEST_QUOTA) context.closed = true; }
        }
    }

    private Message handle(UUID connectionId, Context context, Message request) {
        if (!Message.VERSION.equals(request.version()))
            return error(request, context, ErrorCode.UNSUPPORTED_VERSION, "Expected protocol 1.0");
        if (!CLIENT_COMMANDS.contains(request.command()))
            return error(request, context, ErrorCode.INVALID_STATE, "Server-only command");
        boolean handshake = context.sessionId.isEmpty();
        if (handshake && request.command() != Command.HELLO)
            return error(request, context, ErrorCode.HANDSHAKE_REQUIRED, "Send HELLO first");
        if (handshake ? !request.sessionId().isEmpty() : !context.sessionId.equals(request.sessionId()))
            return error(request, context, ErrorCode.INVALID_SESSION, "Session does not belong to this connection");
        if (!request.requestId().matches("[A-Za-z0-9_-]{1,64}") || !context.requests.add(request.requestId()))
            return error(request, context, ErrorCode.INVALID_PAYLOAD, "Invalid or duplicate request ID");
        if (!context.permit()) return error(request, context, ErrorCode.RATE_LIMITED, "Request rate exceeded");
        if (!validPayload(request)) return error(request, context, ErrorCode.INVALID_PAYLOAD, "Invalid command payload");
        return switch (request.command()) {
            case HELLO -> {
                if (!handshake) yield error(request, context, ErrorCode.INVALID_STATE, "Already handshaken");
                UUID session = UUID.randomUUID();
                if (lobby != null) lobby.register(session, request.payload().get("name"));
                context.sessionId = session.toString(); routes.put(session, connectionId);
                yield ack(request, context, Map.of());
            }
            case PING -> ack(request, context, Map.of());
            case QUIT -> { context.closed = true; yield ack(request, context, Map.of()); }
            case LIST_RULES -> page(request, context, "rule", registry.ruleIds().stream().sorted().map(id -> Map.of("id", id)).toList());
            case LIST_ROOMS -> page(request, context, "room", lobby == null ? List.of() : lobby.rooms().stream().map(room -> Map.of(
                "id", room.roomId().toString(), "ruleId", room.ruleId(), "status", room.status().name(),
                "players", Integer.toString(room.players().size()), "maxPlayers", Integer.toString(room.config().maxPlayers()))).toList());
            case CREATE_ROOM -> {
                if (lobby == null) yield error(request, context, ErrorCode.RULE_NOT_FOUND, "No rules registered");
                var payload = request.payload(); var options = new HashMap<String,String>();
                payload.forEach((key, value) -> { if (key.startsWith("option.")) options.put(key.substring(7), value); });
                var config = new GameConfig(Integer.parseInt(payload.get("maxPlayers")), Integer.parseInt(payload.get("maxTurns")),
                    Long.parseLong(payload.get("turnTimeoutMillis")), options);
                var room = lobby.createRoom(UUID.fromString(context.sessionId), payload.get("ruleId"), config);
                yield ack(request, context, Map.of("roomId", room.roomId().toString()));
            }
            case JOIN_ROOM -> {
                if (lobby == null) yield error(request, context, ErrorCode.ROOM_NOT_FOUND, "Room does not exist");
                var room = lobby.joinRoom(UUID.fromString(context.sessionId), UUID.fromString(request.payload().get("roomId")));
                yield ack(request, context, Map.of("roomId", room.roomId().toString()));
            }
            case LEAVE_ROOM -> {
                if (lobby == null) yield error(request, context, ErrorCode.INVALID_STATE, "Session is not in a room");
                lobby.leaveRoom(UUID.fromString(context.sessionId)); yield ack(request, context, Map.of());
            }
            case START_MATCH -> {
                if(matches==null)yield error(request, context, ErrorCode.INVALID_STATE, "Gameplay runtime unavailable");
                UUID id=matches.startMatch(UUID.fromString(context.sessionId),UUID.fromString(request.payload().get("roomId")));
                yield ack(request,context,Map.of("matchId",id.toString()));
            }
            case ACTION -> {
                if(matches==null)yield error(request,context,ErrorCode.INVALID_STATE,"Gameplay runtime unavailable");
                UUID id=UUID.fromString(request.payload().get("matchId")); int turn=Integer.parseInt(request.payload().get("turn"));
                var action=new HashMap<String,String>();
                request.payload().forEach((key,value)->{if(key.startsWith("action."))action.put(key.substring(7),value);});
                matches.submitAction(UUID.fromString(context.sessionId),id,turn,action);
                yield ack(request,context,Map.of("matchId",id.toString(),"turn",Integer.toString(turn)));
            }
            default -> error(request, context, ErrorCode.INVALID_STATE, "Invalid command direction");
        };
    }

    private Message page(Message request, Context context, String prefix, List<Map<String,String>> items) {
        int offset = Math.min(Integer.parseInt(request.payload().getOrDefault("offset", "0")), items.size());
        int limit = Integer.parseInt(request.payload().getOrDefault("limit", "10"));
        var payload = new HashMap<String,String>(); int count = 0, index = offset;
        for (; index < items.size() && count < limit; index++) {
            var added = new HashSet<String>();
            for (var field : items.get(index).entrySet()) {
                String key = prefix + "." + count + "." + field.getKey(); payload.put(key, field.getValue()); added.add(key);
            }
            payload.put("count", Integer.toString(count + 1)); payload.put("nextOffset", index + 1 < items.size() ? Integer.toString(index + 1) : "-1");
            try { new LengthPrefixedMessageCodec().write(new ByteArrayOutputStream(), ack(request, context, payload)); }
            catch (IOException | IllegalArgumentException ex) {
                added.forEach(payload::remove);
                if (count == 0) return error(request, context, ErrorCode.INTERNAL_ERROR, "List item exceeds protocol limits");
                break;
            }
            count++;
        }
        payload.put("count", Integer.toString(count)); payload.put("nextOffset", index < items.size() ? Integer.toString(index) : "-1");
        return ack(request, context, payload);
    }

    private boolean validPayload(Message request) {
        Map<String,String> p = request.payload();
        try {
            return switch (request.command()) {
                case HELLO -> p.keySet().equals(Set.of("name")) && !p.get("name").isBlank() && p.get("name").length() <= 32;
                case PING, QUIT, LEAVE_ROOM -> p.isEmpty();
                case LIST_RULES, LIST_ROOMS -> Set.of("offset", "limit").containsAll(p.keySet())
                    && (!p.containsKey("offset") || decimal(p.get("offset"), 0, Integer.MAX_VALUE))
                    && (!p.containsKey("limit") || decimal(p.get("limit"), 1, 10));
                case JOIN_ROOM, START_MATCH -> p.keySet().equals(Set.of("roomId")) && uuid(p.get("roomId"));
                case ACTION -> p.keySet().containsAll(Set.of("matchId", "turn")) && uuid(p.get("matchId"))
                    && decimal(p.get("turn"), 0, Integer.MAX_VALUE)
                    && keys(p, Set.of("matchId", "turn"), "action.");
                case CREATE_ROOM -> p.keySet().containsAll(Set.of("ruleId", "maxPlayers", "maxTurns", "turnTimeoutMillis"))
                    && !p.get("ruleId").isBlank() && decimal(p.get("maxPlayers"), 2, 64)
                    && decimal(p.get("maxTurns"), 1, Integer.MAX_VALUE) && decimal(p.get("turnTimeoutMillis"), 100, 300_000)
                    && keys(p, Set.of("ruleId", "maxPlayers", "maxTurns", "turnTimeoutMillis"), "option.");
                default -> false;
            };
        } catch (IllegalArgumentException ex) { return false; }
    }

    private boolean keys(Map<String,String> p, Set<String> fixed, String prefix) {
        return p.keySet().stream().allMatch(key -> fixed.contains(key) || (key.startsWith(prefix) && key.length() > prefix.length()));
    }
    private boolean decimal(String value, int min, int max) {
        if (!value.matches("[0-9]+")) return false;
        int number = Integer.parseInt(value); return number >= min && number <= max;
    }
    private boolean uuid(String value) { return UUID.fromString(value).toString().equals(value); }

    private Message ack(Message request, Context context, Map<String,String> extra) {
        var payload = new HashMap<>(extra); payload.put("for", request.command().name());
        return new Message(Message.VERSION, request.requestId(), context.sessionId, Command.ACK, ErrorCode.NONE, payload);
    }
    private Message error(Message request, Context context, ErrorCode code, String description) {
        return new Message(Message.VERSION, request.requestId(), context.sessionId, Command.ERROR, code, Map.of("message", description));
    }
    public void disconnected(UUID connectionId) {
        Context context = contexts.remove(connectionId);
        if (context == null) return;
        synchronized (context) {
            context.closed = true;
            if (!context.sessionId.isEmpty()) {
                UUID session = UUID.fromString(context.sessionId); routes.remove(session, connectionId);
                if (matches != null) matches.disconnect(session);
                else if (lobby != null) lobby.disconnect(session);
            }
        }
    }
    public int sessionCount() { return contexts.size(); }
}
