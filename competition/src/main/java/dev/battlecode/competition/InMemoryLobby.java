package dev.battlecode.competition;

import dev.battlecode.rulesapi.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import static dev.battlecode.competition.LobbyException.Reason.*;

/** One lock protects sessions, membership and rooms. The notification sink must enqueue without blocking or throwing. */
public final class InMemoryLobby implements Lobby {
    private static final class MutableRoom {
        final UUID id = UUID.randomUUID();
        UUID owner;
        final String ruleId;
        final GameConfig config;
        final LinkedHashSet<UUID> members = new LinkedHashSet<>();
        RoomStatus status = RoomStatus.WAITING;
        long revision = 1;
        MutableRoom(UUID creator, String ruleId, GameConfig config) {
            owner = creator; this.ruleId = ruleId; this.config = config; members.add(creator);
        }
        Room snapshot() { return new Room(id, owner, ruleId, config, status, members); }
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<UUID,PlayerSession> sessions = new HashMap<>();
    private final Map<UUID,UUID> membership = new HashMap<>();
    private final Map<UUID,MutableRoom> rooms = new LinkedHashMap<>();
    private final RuleRegistry registry;
    private final Consumer<RoomNotification> notifications;

    public InMemoryLobby(RuleRegistry registry, Consumer<RoomNotification> notifications) {
        this.registry = Objects.requireNonNull(registry); this.notifications = Objects.requireNonNull(notifications);
    }
    public InMemoryLobby(RuleRegistry registry) { this(registry, ignored -> { }); }

    public PlayerSession register(UUID id, String name) {
        lock.lock();
        try {
            if (sessions.containsKey(id)) throw failure(INVALID_SESSION, "Session already registered");
            var session = new PlayerSession(id, name, SessionStatus.LOBBY);
            sessions.put(id, session); return session;
        } finally { lock.unlock(); }
    }

    public Room createRoom(UUID id, String ruleId, GameConfig config) {
        lock.lock();
        try {
            requireLobby(id);
            var rule = registry.find(ruleId).orElseThrow(() -> failure(RULE_NOT_FOUND, "Rule not registered"));
            try { rule.validateConfig(config); }
            catch (IllegalArgumentException ex) { throw failure(INVALID_CONFIG, ex.getMessage()); }
            var room = new MutableRoom(id, ruleId, config);
            rooms.put(room.id, room); membership.put(id, room.id); setStatus(id, SessionStatus.IN_ROOM);
            emit("CREATED", room.id, RoomStatus.WAITING, room.members.size(), room.members);
            return room.snapshot();
        } finally { lock.unlock(); }
    }

    public Room joinRoom(UUID id, UUID roomId) {
        lock.lock();
        try {
            requireLobby(id);
            var room = rooms.get(roomId);
            if (room == null) throw failure(ROOM_NOT_FOUND, "Room does not exist");
            if (room.status != RoomStatus.WAITING) throw failure(INVALID_STATE, "Room is running");
            if (room.members.size() >= room.config.maxPlayers()) throw failure(ROOM_FULL, "Room is full");
            room.members.add(id); membership.put(id, room.id); setStatus(id, SessionStatus.IN_ROOM);
            room.revision++;
            emit("JOINED", room.id, RoomStatus.WAITING, room.members.size(), room.members);
            return room.snapshot();
        } finally { lock.unlock(); }
    }

    public void leaveRoom(UUID id) {
        lock.lock();
        try {
            requireSession(id);
            if (!membership.containsKey(id)) throw failure(INVALID_STATE, "Session is not in a room");
            if (requireSession(id).status() == SessionStatus.IN_MATCH) throw failure(INVALID_STATE, "Cannot leave a running match");
            removeMembership(id); setStatus(id, SessionStatus.LOBBY);
        } finally { lock.unlock(); }
    }

    public void disconnect(UUID id) {
        lock.lock();
        try {
            if (!sessions.containsKey(id)) return; // Idempotent transport cleanup.
            removeMembership(id); sessions.remove(id);
        } finally { lock.unlock(); }
    }

    private void removeMembership(UUID id) {
        UUID roomId = membership.remove(id);
        if (roomId == null) return;
        MutableRoom room = rooms.get(roomId);
        var affected = new HashSet<>(room.members);
        room.members.remove(id);
        room.revision++;
        if (room.members.isEmpty()) {
            room.status = RoomStatus.CLOSED;
            RoomDetails snapshot = details(room);
            rooms.remove(roomId);
            notifications.accept(new RoomNotification("CLOSED", roomId, RoomStatus.CLOSED, 0, affected, snapshot));
        } else {
            // LinkedHashSet preserves server acceptance order, including leave/rejoin.
            if (room.owner.equals(id)) room.owner = room.members.iterator().next();
            emit("LEFT", roomId, room.status, room.members.size(), affected);
        }
    }

    public List<Room> rooms() {
        lock.lock();
        try { return rooms.values().stream().map(MutableRoom::snapshot).toList(); }
        finally { lock.unlock(); }
    }
    public Optional<PlayerSession> session(UUID id) {
        lock.lock(); try { return Optional.ofNullable(sessions.get(id)); } finally { lock.unlock(); }
    }
    public int sessionCount() { lock.lock(); try { return sessions.size(); } finally { lock.unlock(); } }

    public Optional<RoomDetails> roomDetails(UUID id) {
        lock.lock();
        try {
            requireSession(id);
            UUID roomId = membership.get(id);
            return roomId == null ? Optional.empty() : Optional.of(details(rooms.get(roomId)));
        } finally { lock.unlock(); }
    }
    private RoomDetails details(MutableRoom room) {
        return new RoomDetails(room.id, room.ruleId, room.status, room.owner,
            room.members.stream().map(id -> new RoomDetails.Player(id, sessions.get(id).displayName())).toList(),
            room.config.maxPlayers(), room.revision);
    }

    // Package-private lifecycle transaction helpers; caller holds the shared competition lock.
    ReentrantLock lifecycleLock() { return lock; }
    Room requireRoom(UUID session, UUID roomId) {
        requireSession(session);
        MutableRoom room = rooms.get(roomId);
        if (room == null) throw failure(ROOM_NOT_FOUND, "Room does not exist");
        if (!roomId.equals(membership.get(session))) throw failure(INVALID_STATE, "Session is not a room member");
        return room.snapshot();
    }
    void running(UUID roomId) {
        MutableRoom room = rooms.get(roomId); room.status = RoomStatus.RUNNING;
        room.revision++;
        room.members.forEach(id -> setStatus(id, SessionStatus.IN_MATCH));
    }
    void waiting(UUID roomId) {
        MutableRoom room = rooms.get(roomId); room.status = RoomStatus.WAITING;
        room.members.forEach(id -> setStatus(id, SessionStatus.IN_ROOM));
    }
    void finish(UUID roomId) {
        MutableRoom room = rooms.remove(roomId); if (room == null) return;
        room.status = RoomStatus.FINISHED;
        room.revision++;
        for (UUID id : room.members) {
            membership.remove(id);
            if (sessions.containsKey(id)) {
                setStatus(id, SessionStatus.IN_ROOM);
                setStatus(id, SessionStatus.LOBBY);
            }
        }
        var closed = new RoomDetails(room.id, room.ruleId, RoomStatus.CLOSED, room.owner, List.of(), room.config.maxPlayers(), room.revision);
        notifications.accept(new RoomNotification("CLOSED", roomId, RoomStatus.CLOSED, 0, room.members, closed));
    }

    private void requireLobby(UUID id) {
        PlayerSession session = requireSession(id);
        if (session.status() != SessionStatus.LOBBY || membership.containsKey(id))
            throw failure(INVALID_STATE, "Session already belongs to a room");
    }
    private PlayerSession requireSession(UUID id) {
        var session = sessions.get(id);
        if (session == null || session.status() == SessionStatus.CLOSED) throw failure(INVALID_SESSION, "Session is closed or unknown");
        return session;
    }
    private void setStatus(UUID id, SessionStatus status) {
        var session = sessions.get(id); sessions.put(id, new PlayerSession(id, session.displayName(), status));
    }
    private LobbyException failure(LobbyException.Reason reason, String message) { return new LobbyException(reason, message); }
    private void emit(String type, UUID roomId, RoomStatus status, int count, Set<UUID> recipients) {
        MutableRoom room = rooms.get(roomId);
        RoomDetails snapshot = room == null ? null : details(room);
        notifications.accept(new RoomNotification(type, roomId, status, count, recipients, snapshot));
    }
}
