package dev.battlecode.competition;

import dev.battlecode.engine.*;
import dev.battlecode.rulesapi.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/** Lifecycle lock -> per-match engine lock. Notification sink only enqueues; never blocks or throws. */
public final class DefaultMatchManager implements MatchManager, BotMatchAccess {
    private static final class Match {
        final UUID id;
        final UUID room;
        final LinkedHashSet<UUID> connected;
        final GameRule rule;
        final boolean bot;
        GameState latest;
        long deadlineLowerBound;
        Lease lease;

        Match(UUID id, Room room, GameRule rule, GameState initial, boolean bot, long startedAt) {
            this.id = id;
            this.room = room.roomId();
            connected = new LinkedHashSet<>(room.players());
            this.rule=rule;this.latest=initial;this.bot=bot;
            deadlineLowerBound=startedAt+TimeUnit.MILLISECONDS.toNanos(room.config().turnTimeoutMillis());
        }
    }

    private final InMemoryLobby lobby;
    private final GameEngine engine;
    private final RuleRegistry registry;
    private final Consumer<MatchNotification> notifications;
    private final ReentrantLock lock;
    private final Map<UUID, Match> active = new HashMap<>();
    private final Map<UUID, UUID> membership = new HashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon(true).name("battlecode-match-timeouts").factory());
    private boolean closed;

    public DefaultMatchManager(InMemoryLobby lobby, GameEngine engine, RuleRegistry registry,
                               Consumer<MatchNotification> notifications) {
        this.lobby = Objects.requireNonNull(lobby);
        this.engine = Objects.requireNonNull(engine);
        this.registry = Objects.requireNonNull(registry);
        this.notifications = Objects.requireNonNull(notifications);
        lock = lobby.lifecycleLock();
        scheduler.scheduleWithFixedDelay(this::tick, 10, 10, TimeUnit.MILLISECONDS);
    }

    public UUID startMatch(UUID session, UUID roomId) {
        return startInternal(session,roomId,false);
    }

    public UUID startBotMatch(UUID session, UUID roomId) { return startInternal(session,roomId,true); }

    private UUID startInternal(UUID session, UUID roomId, boolean bot) {
        lock.lock();
        try {
            if (closed) throw failure(MatchException.Reason.INVALID_STATE, "Server is shutting down");
            Room room = lobby.requireRoom(session, roomId);
            if(bot && room.config().turnTimeoutMillis()!=10000) throw failure(MatchException.Reason.INVALID_STATE,"BOT deadline must be 10000ms");
            if (!room.ownerId().equals(session)) throw failure(MatchException.Reason.NOT_OWNER, "Only the host can start");
            if (room.status() != RoomStatus.WAITING) throw failure(MatchException.Reason.INVALID_STATE, "Room already started");
            GameRule rule = registry.find(room.ruleId()).orElseThrow(() ->
                    new LobbyException(LobbyException.Reason.RULE_NOT_FOUND, "Rule not registered"));
            if (!rule.supportsGameplay()) throw failure(MatchException.Reason.INVALID_STATE, "Rule has no gameplay capability");
            UUID id = UUID.randomUUID();
            // Deterministic rule roster: host first, remaining IDs sorted. Host migration uses Lobby join order.
            var players = new ArrayList<UUID>();
            players.add(room.ownerId());
            room.players().stream().filter(player -> !player.equals(room.ownerId())).sorted().forEach(players::add);
            GameState state;
            long startedAt=System.nanoTime();
            try {
                state = engine.start(id, rule, room.config(), players);
            } catch (IllegalArgumentException ex) {
                throw new LobbyException(LobbyException.Reason.INVALID_CONFIG, "Invalid rule config");
            }
            // Initialization has succeeded; publish the lifecycle transaction together.
            Match match = new Match(id, room,rule,state,bot,startedAt);
            active.put(id, match);
            match.connected.forEach(player -> membership.put(player, id));
            lobby.running(roomId);
            publish(match, "STARTED");
            if (state.status() != MatchStatus.RUNNING) terminal(match);
            return id;
        } finally {
            lock.unlock();
        }
    }

    public GameState submitAction(UUID session, UUID id, Map<String, String> action) {
        return submitInternal(session, id, -1, action);
    }

    public GameState submitAction(UUID session, UUID id, int expectedTurn, Map<String, String> action) {
        if (expectedTurn < 0) throw failure(MatchException.Reason.INVALID_STATE, "Negative expected turn");
        return submitInternal(session, id, expectedTurn, action);
    }

    private GameState submitInternal(UUID session, UUID id, int expectedTurn, Map<String, String> action) {
        lock.lock();
        try {
            if (closed || !id.equals(membership.get(session))) {
                throw failure(MatchException.Reason.INVALID_STATE, "Session is not in this running match");
            }
            Match match = active.get(id);
            if(match.bot) throw failure(MatchException.Reason.INVALID_STATE,"Server-owned bot seats reject manual actions");
            GameState state;
            try {
                state = expectedTurn < 0 ? engine.submit(id, session, action)
                        : engine.submit(id, session, expectedTurn, action);
                match.latest=state;
            } catch (EngineException ex) {
                if (engine.result(id).isPresent()) terminal(match);
                throw ex;
            }
            if (state.status() == MatchStatus.RUNNING) publish(match, "STATE");
            else terminal(match);
            return state;
        } finally {
            lock.unlock();
        }
    }

    public void disconnect(UUID session) {
        lock.lock();
        try {
            UUID id = membership.remove(session);
            Match match = id == null ? null : active.get(id);
            if (match != null) {
                if(match.bot) {
                    engine.cancel(id,"BOT_SEAT_DISCONNECTED");terminal(match);lobby.disconnect(session);return;
                }
                match.connected.remove(session);
                GameState state = engine.disconnect(id, session);
                match.latest=state;
                if (state.status() == MatchStatus.RUNNING) publish(match, "STATE");
                else terminal(match);
            }
            lobby.disconnect(session);
        } finally {
            lock.unlock();
        }
    }

    public Optional<GameResult> result(UUID id) { return engine.result(id); }

    private void tick() {
        lock.lock();
        try {
            if (closed) return;
            for (Match match : List.copyOf(active.values())) {
                if(match.bot) {
                    if(System.nanoTime()>=match.deadlineLowerBound) {engine.cancel(match.id,"BOT_INFRASTRUCTURE_DEADLINE");terminal(match);}
                    continue;
                }
                if (match.connected.isEmpty()) continue;
                UUID player = match.connected.iterator().next();
                PlayerSnapshot before = engine.snapshot(match.id, player);
                GameState state = engine.timeout(match.id, before.generation());
                match.latest=state;
                if (state.status() != MatchStatus.RUNNING) terminal(match);
                else if (engine.snapshot(match.id, player).generation() != before.generation()) publish(match, "STATE");
            }
        } catch (RuntimeException ex) {
            System.getLogger(getClass().getName()).log(System.Logger.Level.ERROR, "Match scheduler failed", ex);
        } finally {
            lock.unlock();
        }
    }

    private void publish(Match match, String type) {
        var snapshots = new HashMap<UUID, PlayerSnapshot>();
        match.connected.forEach(player -> snapshots.put(player, engine.snapshot(match.id, player)));
        notifications.accept(new MatchNotification(type, snapshots, engine.result(match.id).orElse(null)));
    }

    public Optional<Lease> captureBotTurn(UUID id, java.util.function.BiFunction<RuleState,UUID,dev.battlecode.rulesapi.bot.BotObservation> projector) {
        lock.lock();
        try {
            Match match=active.get(id);
            if(closed || match==null || !match.bot) return Optional.empty();
            if(match.lease!=null) throw failure(MatchException.Reason.INVALID_STATE,"One outstanding bot lease per match");
            UUID player=match.rule.currentPlayer(match.latest.ruleState());
            PlayerSnapshot snapshot=engine.snapshot(id,player);
            if(snapshot.status()!=MatchStatus.RUNNING)return Optional.empty();
            match.lease=new Lease(id,player,snapshot.turn(),snapshot.generation(),UUID.randomUUID(),
                Objects.requireNonNull(projector.apply(match.latest.ruleState(),player)));
            return Optional.of(match.lease);
        } finally {lock.unlock();}
    }

    private Match validLease(Lease lease) {
        Match match=active.get(lease.matchId());
        if(closed || match==null || !match.bot || match.lease==null || !match.lease.equals(lease))return null;
        PlayerSnapshot snapshot=engine.snapshot(lease.matchId(),lease.playerId());
        return snapshot.status()==MatchStatus.RUNNING && snapshot.turn()==lease.expectedTurn() && snapshot.generation()==lease.generation()?match:null;
    }

    public Optional<GameState> commitBotTurn(Lease lease, Map<String,String> action) { return commitBot(lease,action); }
    public Optional<GameState> forfeitBotTurn(Lease lease) { return commitBot(lease,Map.of()); }
    private Optional<GameState> commitBot(Lease lease,Map<String,String> action) {
        lock.lock();
        try {
            Match match=validLease(lease);if(match==null)return Optional.empty();
            long before=System.nanoTime();
            GameState state;
            try {
                state=engine.submit(lease.matchId(),lease.playerId(),lease.expectedTurn(),action);
            } catch(EngineException ex) {
                // No generic engine timeout for BOT games: unknown deadline/crash is infrastructure.
                engine.cancel(match.id,"BOT_COMMIT_FAILURE");terminal(match);return Optional.empty();
            }
            match.latest=state;match.lease=null;
            // BOT config was checked at admission. Engine owns the actual deadline, this is conservative.
            match.deadlineLowerBound=before+TimeUnit.SECONDS.toNanos(10);
            if(state.status()==MatchStatus.RUNNING)publish(match,"STATE");else terminal(match);
            return Optional.of(state);
        } finally {lock.unlock();}
    }
    public boolean cancelBotMatch(UUID id,String reason) {
        lock.lock();
        try {Match match=active.get(id);if(match==null || !match.bot)return false;
            engine.cancel(id,reason);terminal(match);return true;
        } finally {lock.unlock();}
    }

    private void terminal(Match match) {
        if (active.remove(match.id) == null) return;
        String type = "CANCELLED";
        if (!match.connected.isEmpty()
                && engine.snapshot(match.id, match.connected.iterator().next()).status() == MatchStatus.FINISHED) {
            type = "FINISHED";
        }
        publish(match, type);
        match.connected.forEach(membership::remove);
        lobby.finish(match.room);
    }

    public void shutdown() {
        lock.lock();
        try {
            if (closed) return;
            closed = true;
            for (Match match : List.copyOf(active.values())) {
                engine.cancel(match.id, "SERVER_SHUTDOWN");
                terminal(match);
            }
            scheduler.shutdownNow();
        } finally {
            lock.unlock();
        }
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return scheduler.awaitTermination(timeout, unit);
    }

    public boolean isTerminated() { return scheduler.isTerminated(); }

    public int activeMatches() {
        lock.lock();
        try { return active.size(); }
        finally { lock.unlock(); }
    }

    private MatchException failure(MatchException.Reason reason, String message) {
        return new MatchException(reason, message);
    }
}
