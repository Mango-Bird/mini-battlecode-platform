package dev.battlecode.engine;

import dev.battlecode.rulesapi.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import static dev.battlecode.engine.EngineException.Reason.*;

/** Trusted rules execute synchronously under a per-match lock. No network or event callbacks. */
public final class DefaultGameEngine implements GameEngine {
    private static final int MAX_VIEW_FIELDS = 120;
    private static final int MAX_VIEW_BYTES = 60_000;
    private static final int MAX_REASON_BYTES = 2048;

    private static final class Match {
        final ReentrantLock lock = new ReentrantLock();
        final UUID id;
        final GameRule rule;
        final GameConfig config;
        final List<UUID> players;
        final Set<UUID> connected;
        final Set<UUID> submitted = new HashSet<>();
        GameState state;
        GameResult result;
        Map<UUID, Map<String, String>> views = Map.of();
        long generation;
        long deadline;
        long epoch;

        Match(UUID id, GameRule rule, GameConfig config, List<UUID> players, RuleState initial) {
            this.id = id;
            this.rule = rule;
            this.config = config;
            this.players = List.copyOf(players);
            connected = new HashSet<>(players);
            state = new GameState(id, MatchStatus.RUNNING, 0, initial);
        }
    }

    private final Map<UUID, Match> matches = new ConcurrentHashMap<>();

    /** Creation is serialized so an existing ID never invokes initialState twice. */
    public synchronized GameState start(UUID id, GameRule rule, GameConfig config, List<UUID> players) {
        Objects.requireNonNull(id);
        Objects.requireNonNull(rule);
        Objects.requireNonNull(config);
        if (matches.containsKey(id)) throw failure(INVALID_STATE, "Match already exists");
        var roster = List.copyOf(players);
        boolean executable;
        int minimum;
        try {
            executable = rule.supportsGameplay();
            rule.validateConfig(config);
            minimum = rule.minimumPlayers(config);
        } catch (IllegalArgumentException ex) {
            throw ex; // Config rejection is a caller error, not a running match failure.
        } catch (RuntimeException ex) {
            throw failure(INTERNAL_ERROR, "Rule configuration failed");
        }
        if (!executable) throw failure(INVALID_STATE, "Rule has no gameplay capability");
        if (minimum < 2 || minimum > config.maxPlayers() || roster.size() < minimum
                || roster.size() > config.maxPlayers() || new HashSet<>(roster).size() != roster.size()) {
            throw failure(INVALID_STATE, "Invalid player roster");
        }
        Match match;
        try {
            match = new Match(id, rule, config, roster, Objects.requireNonNull(rule.initialState(config, roster)));
            openTurn(match);
            update(match, match.state.ruleState(), false);
        } catch (RuntimeException ex) {
            System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING, "Rule initialization failed", ex);
            throw failure(INTERNAL_ERROR, "Rule initialization failed");
        }
        matches.put(id, match);
        return match.state;
    }

    public GameState submit(UUID id, UUID player, Map<String, String> action) {
        return submitInternal(id, player, -1, action);
    }

    public GameState submit(UUID id, UUID player, int expectedTurn, Map<String, String> action) {
        if (expectedTurn < 0) throw failure(INVALID_STATE, "Negative expected turn");
        return submitInternal(id, player, expectedTurn, action);
    }

    private GameState submitInternal(UUID id, UUID player, int expectedTurn, Map<String, String> action) {
        Match match = match(id);
        match.lock.lock();
        try {
            running(match);
            member(match, player);
            if (expectedTurn >= 0 && expectedTurn != match.state.turn()) throw failure(INVALID_STATE, "Stale turn");
            if (System.nanoTime() >= match.deadline) throw failure(TIMEOUT, "Turn deadline expired");
            var input = Map.copyOf(action);
            try {
                if (match.submitted.contains(player) || !eligible(match).contains(player)) {
                    throw failure(NOT_YOUR_TURN, "Player cannot act in this turn");
                }
                if (!match.rule.isActionAllowed(match.state.ruleState(), player, input)) {
                    throw failure(ACTION_REJECTED, "Rule rejected action");
                }
                RuleState before = match.state.ruleState();
                RuleState after = Objects.requireNonNull(match.rule.applyAction(before, player, input));
                boolean advance = match.rule.isTurnComplete(before, after);
                update(match, after, advance);
                if (!advance) match.submitted.add(player);
                return match.state;
            } catch (EngineException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                ruleFailure(match, ex);
                throw failure(INTERNAL_ERROR, "Rule execution failed");
            }
        } finally {
            match.lock.unlock();
        }
    }

    public GameState timeout(UUID id, UUID player) {
        Match match = match(id);
        match.lock.lock();
        try {
            member(match, player);
            return timeout(id, match.generation);
        } finally {
            match.lock.unlock();
        }
    }

    public GameState timeout(UUID id, long generation) {
        Match match = match(id);
        match.lock.lock();
        try {
            if (match.state.status() != MatchStatus.RUNNING || generation != match.generation
                    || System.nanoTime() < match.deadline) return match.state;
            try {
                RuleState next = match.state.ruleState();
                for (UUID player : eligible(match).stream().sorted().toList()) {
                    if (match.submitted.contains(player)) continue;
                    next = Objects.requireNonNull(match.rule.onTurnTimeout(next, player));
                    if (match.rule.evaluate(next).finished()) break;
                }
                update(match, next, true);
                return match.state;
            } catch (RuntimeException ex) {
                ruleFailure(match, ex);
                return match.state;
            }
        } finally {
            match.lock.unlock();
        }
    }

    public GameState disconnect(UUID id, UUID player) {
        Match match = match(id);
        match.lock.lock();
        try {
            if (match.state.status() != MatchStatus.RUNNING || !match.connected.remove(player)) return match.state;
            try {
                RuleState before = match.state.ruleState();
                RuleState next = Objects.requireNonNull(match.rule.onDisconnect(before, player));
                if (match.connected.isEmpty()) cancelLocked(match, "NO_CONNECTED_PLAYERS");
                else update(match, next, match.rule.isTurnComplete(before, next));
            } catch (RuntimeException ex) {
                ruleFailure(match, ex);
            }
            return match.state;
        } finally {
            match.lock.unlock();
        }
    }

    public PlayerSnapshot snapshot(UUID id, UUID player) {
        Match match = match(id);
        match.lock.lock();
        try {
            member(match, player);
            return new PlayerSnapshot(id, match.state.status(), match.state.turn(), match.generation,
                    match.epoch, match.views.getOrDefault(player, Map.of()));
        } finally {
            match.lock.unlock();
        }
    }

    public Optional<GameResult> result(UUID id) {
        Match match = matches.get(id);
        if (match == null) return Optional.empty();
        match.lock.lock();
        try {
            return Optional.ofNullable(match.result);
        } finally {
            match.lock.unlock();
        }
    }

    public void cancel(UUID id, String reason) {
        Objects.requireNonNull(reason);
        if (bytes(reason) > MAX_REASON_BYTES) throw new IllegalArgumentException("Cancellation reason too large");
        Match match = match(id);
        match.lock.lock();
        try {
            if (match.state.status() == MatchStatus.RUNNING) cancelLocked(match, reason);
        } finally {
            match.lock.unlock();
        }
    }

    private void update(Match match, RuleState next, boolean advance) {
        // Validate the complete candidate, including every private view, before committing it.
        RuleOutcome outcome = Objects.requireNonNull(match.rule.evaluate(next));
        if (!new HashSet<>(match.players).containsAll(outcome.winners()) || bytes(outcome.reason()) > MAX_REASON_BYTES) {
            throw new IllegalStateException("Invalid rule outcome");
        }
        int turn = match.state.turn();
        if (advance && turn < Integer.MAX_VALUE) turn++;
        MatchStatus status = outcome.finished() ? MatchStatus.FINISHED : MatchStatus.RUNNING;
        Map<UUID, Map<String, String>> views = status == MatchStatus.RUNNING ? privateViews(match, next) : Map.of();
        match.state = new GameState(match.id, status, turn, next);
        match.views = views;
        if (outcome.finished()) match.result = new GameResult(match.id, outcome.winners(), outcome.reason());
        else if (advance && turn >= match.config.maxTurns()) cancelLocked(match, "MAX_TURNS");
        else if (advance) openTurn(match);
    }

    private Map<UUID, Map<String, String>> privateViews(Match match, RuleState state) {
        Set<UUID> active = Set.copyOf(match.rule.activePlayers(state));
        if (!new HashSet<>(match.players).containsAll(active)) throw new IllegalStateException("Unknown active player");
        var views = new HashMap<UUID, Map<String, String>>();
        for (UUID player : match.connected) {
            var view = Map.copyOf(match.rule.viewFor(state, player));
            if (view.size() > MAX_VIEW_FIELDS) throw new IllegalStateException("View exceeds field limit");
            long size = 0;
            for (var entry : view.entrySet()) {
                if (entry.getKey().isEmpty()) throw new IllegalStateException("Empty view key");
                size += 4L + bytes("view." + entry.getKey()) + bytes(entry.getValue());
            }
            if (size > MAX_VIEW_BYTES) throw new IllegalStateException("View exceeds frame budget");
            views.put(player, view);
        }
        return Map.copyOf(views);
    }

    private Set<UUID> eligible(Match match) {
        var eligible = new HashSet<>(match.rule.activePlayers(match.state.ruleState()));
        eligible.retainAll(match.connected);
        return eligible;
    }

    private void openTurn(Match match) {
        match.generation++;
        match.submitted.clear();
        match.deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(match.config.turnTimeoutMillis());
        match.epoch = System.currentTimeMillis() + match.config.turnTimeoutMillis();
    }

    private void cancelLocked(Match match, String reason) {
        match.state = new GameState(match.id, MatchStatus.CANCELLED, match.state.turn(), match.state.ruleState());
        match.result = new GameResult(match.id, Set.of(), reason);
        match.views = Map.of();
    }

    private void ruleFailure(Match match, RuntimeException ex) {
        cancelLocked(match, "RULE_ERROR");
        System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING, "Rule failed in match " + match.id, ex);
    }

    private int bytes(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }

    private Match match(UUID id) {
        Match match = matches.get(id);
        if (match == null) throw failure(INVALID_STATE, "Unknown match");
        return match;
    }

    private void running(Match match) {
        if (match.state.status() != MatchStatus.RUNNING) throw failure(INVALID_STATE, "Match is terminal");
    }

    private void member(Match match, UUID player) {
        if (!match.players.contains(player)) throw failure(INVALID_STATE, "Player is not a match member");
    }

    private EngineException failure(EngineException.Reason reason, String message) {
        return new EngineException(reason, message);
    }
}
