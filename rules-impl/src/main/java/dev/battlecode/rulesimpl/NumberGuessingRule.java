package dev.battlecode.rulesimpl;

import dev.battlecode.rulesapi.GameConfig;
import dev.battlecode.rulesapi.GameRule;
import dev.battlecode.rulesapi.RuleOutcome;
import dev.battlecode.rulesapi.RuleState;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntUnaryOperator;

/** Stateless secret-number rule. A per-match random secret is held only by immutable RuleState. */
public final class NumberGuessingRule implements GameRule {
    public static final String ID = "number-guessing";
    public static final String MIN_NUMBER = "minNumber";
    public static final String MAX_NUMBER = "maxNumber";
    private static final int DEFAULT_MIN = 1;
    private static final int DEFAULT_MAX = 100;
    private static final long MAX_RANGE_SIZE = 1_000_000L;
    // Four fields per visible guess plus seven fixed fields fits the engine's 120-field view limit.
    private static final int PUBLIC_HISTORY_LIMIT = 28;
    private final IntUnaryOperator randomOffset;

    private record Guess(int turn, UUID player, int value, String hint) { }

    private record State(List<UUID> players, int secret, int min, int max, int maxTurns,
                         int currentIndex, int turnsTaken, List<Guess> history,
                         UUID winner, String finishReason) implements RuleState {
        private State {
            players = List.copyOf(players);
            history = List.copyOf(history);
            if (winner == null && finishReason != null || winner != null && finishReason == null)
                throw new IllegalArgumentException("Winner and finish reason must be set together");
        }
    }

    public NumberGuessingRule() {
        this(bound -> ThreadLocalRandom.current().nextInt(bound));
    }

    /** Injection point for deterministic tests; production uses the no-argument constructor. */
    public NumberGuessingRule(IntUnaryOperator randomOffset) { this.randomOffset = Objects.requireNonNull(randomOffset); }

    @Override public String id() { return ID; }

    @Override public void validateConfig(GameConfig config) {
        Objects.requireNonNull(config);
        if (config.maxPlayers() != 2) throw new IllegalArgumentException("Number Guessing requires maxPlayers=2");
        if (!Set.of(MIN_NUMBER, MAX_NUMBER).containsAll(config.options().keySet()))
            throw new IllegalArgumentException("Unknown Number Guessing option");
        int min = option(config, MIN_NUMBER, DEFAULT_MIN), max = option(config, MAX_NUMBER, DEFAULT_MAX);
        long size = (long) max - min + 1L;
        if (min >= max || size < 2 || size > MAX_RANGE_SIZE)
            throw new IllegalArgumentException("Number range must contain 2..1000000 values");
    }

    @Override public int minimumPlayers(GameConfig config) { return 2; }

    @Override public RuleState initialState(GameConfig config, List<UUID> players) {
        validateConfig(config);
        if (players.size() != 2 || new HashSet<>(players).size() != 2)
            throw new IllegalArgumentException("Number Guessing requires exactly two players");
        int min = option(config, MIN_NUMBER, DEFAULT_MIN), max = option(config, MAX_NUMBER, DEFAULT_MAX);
        int bound = Math.toIntExact((long) max - min + 1L);
        int offset = randomOffset.applyAsInt(bound);
        if (offset < 0 || offset >= bound) throw new IllegalArgumentException("Random source returned an out-of-range offset");
        return new State(players, (int) ((long) min + offset), min, max, config.maxTurns(), 0, 0,
                List.of(), null, null);
    }

    @Override public UUID currentPlayer(RuleState state) {
        State value = state(state);
        return value.players().get(value.currentIndex());
    }

    @Override public boolean isActionAllowed(RuleState raw, UUID playerId, Map<String, String> action) {
        State state = state(raw);
        if (state.winner() != null || !state.players().get(state.currentIndex()).equals(playerId)
                || !action.keySet().equals(Set.of("guess"))) return false;
        Integer guess = parseInteger(action.get("guess"));
        return guess != null && guess >= state.min() && guess <= state.max();
    }

    @Override public RuleState applyAction(RuleState raw, UUID playerId, Map<String, String> action) {
        State before = state(raw);
        if (!isActionAllowed(before, playerId, action)) throw new IllegalArgumentException("Invalid guess or player turn");
        int value = Integer.parseInt(action.get("guess"));
        String hint = value == before.secret() ? "CORRECT" : value < before.secret() ? "HIGHER" : "LOWER";
        var history = new ArrayList<>(before.history());
        history.add(new Guess(before.turnsTaken() + 1, playerId, value, hint));
        if (history.size() > PUBLIC_HISTORY_LIMIT) history.removeFirst();
        UUID winner = value == before.secret() ? playerId : null;
        String reason = winner == null ? null : "CORRECT_GUESS;secret=" + before.secret();
        return new State(before.players(), before.secret(), before.min(), before.max(), before.maxTurns(),
                (before.currentIndex() + 1) % 2, before.turnsTaken() + 1, history, winner, reason);
    }

    @Override public RuleState onTurnTimeout(RuleState raw, UUID playerId) {
        State before = state(raw);
        if (before.winner() != null || !before.players().get(before.currentIndex()).equals(playerId)) return before;
        return new State(before.players(), before.secret(), before.min(), before.max(), before.maxTurns(),
                (before.currentIndex() + 1) % 2, before.turnsTaken() + 1, before.history(), null, null);
    }

    @Override public RuleState onDisconnect(RuleState raw, UUID playerId) {
        State before = state(raw);
        if (before.winner() != null || !before.players().contains(playerId)) return before;
        UUID survivor = before.players().stream().filter(player -> !player.equals(playerId)).findFirst().orElseThrow();
        return new State(before.players(), before.secret(), before.min(), before.max(), before.maxTurns(),
                before.currentIndex(), before.turnsTaken(), before.history(), survivor, "FORFEIT_DISCONNECT");
    }

    @Override public RuleOutcome evaluate(RuleState raw) {
        State state = state(raw);
        if (state.winner() == null) return new RuleOutcome(false, Set.of(), "NUMBER_GUESSING_RUNNING");
        return new RuleOutcome(true, Set.of(state.winner()), state.finishReason());
    }

    @Override public Map<String, String> viewFor(RuleState raw, UUID playerId) {
        State state = state(raw);
        if (!state.players().contains(playerId)) throw new IllegalArgumentException("Unknown player");
        var view = new LinkedHashMap<String, String>();
        view.put("minNumber", Integer.toString(state.min()));
        view.put("maxNumber", Integer.toString(state.max()));
        view.put("currentPlayer", state.players().get(state.currentIndex()).toString());
        view.put("yourTurn", Boolean.toString(state.players().get(state.currentIndex()).equals(playerId)));
        view.put("turnsTaken", Integer.toString(state.turnsTaken()));
        view.put("maxTurns", Integer.toString(state.maxTurns()));
        view.put("historyCount", Integer.toString(state.history().size()));
        for (int i = 0; i < state.history().size(); i++) {
            Guess guess = state.history().get(i);
            view.put("history." + i + ".player", guess.player().toString());
            view.put("history." + i + ".guess", Integer.toString(guess.value()));
            view.put("history." + i + ".hint", guess.hint());
            view.put("history." + i + ".turn", Integer.toString(guess.turn()));
        }
        // The terminal event carries GameResult.reason; snapshots never include the secret.
        return Map.copyOf(view);
    }

    private int option(GameConfig config, String key, int fallback) {
        String raw = config.options().get(key);
        if (raw == null) return fallback;
        try { return Integer.parseInt(raw); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException(key + " must be an integer", ex); }
    }

    private Integer parseInteger(String raw) {
        if (raw == null || !raw.matches("-?(0|[1-9][0-9]*)")) return null;
        try { return Integer.valueOf(raw); }
        catch (NumberFormatException ex) { return null; }
    }

    private State state(RuleState state) {
        if (!(state instanceof State value)) throw new IllegalArgumentException("State does not belong to Number Guessing");
        return value;
    }
}
