package dev.battlecode.rulesimpl;

import dev.battlecode.rulesapi.GameConfig;
import dev.battlecode.rulesapi.GameRule;
import dev.battlecode.rulesapi.RuleOutcome;
import dev.battlecode.rulesapi.RuleState;

import java.util.*;

/** Stateless, simultaneous Rock-Paper-Scissors rule. All mutable match data is in immutable State. */
public final class RockPaperScissorsRule implements GameRule {
    public static final String ID = "rock-paper-scissors";
    public static final String ROUNDS_TO_WIN = "roundsToWin";
    private static final int DEFAULT_ROUNDS_TO_WIN = 3;

    private enum Choice { ROCK, PAPER, SCISSORS }

    private record RoundReveal(int number, Map<UUID, Choice> choices, UUID winner) {
        private RoundReveal { choices = Map.copyOf(choices); }
    }

    private record State(List<UUID> players, Map<UUID, Integer> scores, int roundsToWin,
                         int round, Map<UUID, Choice> choices, Set<UUID> timedOut,
                         RoundReveal lastReveal, UUID winner, String finishReason) implements RuleState {
        private State {
            players = List.copyOf(players);
            scores = Map.copyOf(scores);
            choices = Map.copyOf(choices);
            timedOut = Set.copyOf(timedOut);
            if (winner == null && finishReason != null || winner != null && finishReason == null)
                throw new IllegalArgumentException("Winner and finish reason must be set together");
        }
    }

    @Override public String id() { return ID; }

    @Override public void validateConfig(GameConfig config) {
        Objects.requireNonNull(config);
        if (config.maxPlayers() != 2) throw new IllegalArgumentException("Rock-Paper-Scissors requires maxPlayers=2");
        if (!Set.of(ROUNDS_TO_WIN).containsAll(config.options().keySet()))
            throw new IllegalArgumentException("Unknown Rock-Paper-Scissors option");
        String value = config.options().get(ROUNDS_TO_WIN);
        if (value != null) {
            try {
                int rounds = Integer.parseInt(value);
                if (rounds < 1 || rounds > 10) throw new IllegalArgumentException("roundsToWin must be in 1..10");
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("roundsToWin must be an integer in 1..10", ex);
            }
        }
    }

    @Override public int minimumPlayers(GameConfig config) { return 2; }

    @Override public RuleState initialState(GameConfig config, List<UUID> players) {
        validateConfig(config);
        if (players.size() != 2 || new HashSet<>(players).size() != 2)
            throw new IllegalArgumentException("Rock-Paper-Scissors requires exactly two players");
        int target = Integer.parseInt(config.options().getOrDefault(ROUNDS_TO_WIN, Integer.toString(DEFAULT_ROUNDS_TO_WIN)));
        var scores = new LinkedHashMap<UUID, Integer>();
        players.forEach(player -> scores.put(player, 0));
        return new State(players, scores, target, 1, Map.of(), Set.of(), null, null, null);
    }

    @Override public UUID currentPlayer(RuleState state) { return state(state).players().getFirst(); }

    @Override public Set<UUID> activePlayers(RuleState state) { return Set.copyOf(state(state).players()); }

    @Override public boolean isTurnComplete(RuleState before, RuleState after) {
        State a = state(before), b = state(after);
        return b.winner() != null || b.round() != a.round();
    }

    @Override public boolean isActionAllowed(RuleState raw, UUID playerId, Map<String, String> action) {
        State state = state(raw);
        return state.winner() == null && state.players().contains(playerId) && !state.choices().containsKey(playerId)
                && action.keySet().equals(Set.of("choice")) && parseChoice(action.get("choice")) != null;
    }

    @Override public RuleState applyAction(RuleState raw, UUID playerId, Map<String, String> action) {
        State before = state(raw);
        if (!isActionAllowed(before, playerId, action)) throw new IllegalArgumentException("Invalid or duplicate choice");
        var choices = new LinkedHashMap<>(before.choices());
        choices.put(playerId, parseChoice(action.get("choice")));
        State submitted = copy(before, before.scores(), before.round(), choices, before.timedOut(), before.lastReveal(), null, null);
        if (choices.size() < 2) return submitted;
        return resolve(submitted, choices);
    }

    @Override public RuleState onTurnTimeout(RuleState raw, UUID playerId) {
        State before = state(raw);
        if (before.winner() != null || !before.players().contains(playerId) || before.choices().containsKey(playerId)) return before;
        if (before.choices().size() == 1) {
            UUID submitted = before.choices().keySet().iterator().next();
            return resolve(before, before.choices(), submitted);
        }
        var timedOut = new HashSet<>(before.timedOut());
        timedOut.add(playerId);
        State marked = copy(before, before.scores(), before.round(), before.choices(), timedOut, before.lastReveal(), null, null);
        // The engine calls once for each pending player in the simultaneous turn.
        return timedOut.size() == 2 ? resolve(marked, Map.of(), null) : marked;
    }

    @Override public RuleState onDisconnect(RuleState raw, UUID playerId) {
        State before = state(raw);
        if (before.winner() != null || !before.players().contains(playerId)) return before;
        UUID survivor = before.players().stream().filter(player -> !player.equals(playerId)).findFirst().orElseThrow();
        return copy(before, before.scores(), before.round(), before.choices(), before.timedOut(), before.lastReveal(),
                survivor, "FORFEIT_DISCONNECT");
    }

    @Override public RuleOutcome evaluate(RuleState raw) {
        State state = state(raw);
        if (state.winner() == null) return new RuleOutcome(false, Set.of(), "RPS_RUNNING");
        String reveal = state.lastReveal() == null ? "" : ";round=" + state.lastReveal().number()
                + ";choices=" + choiceFor(state.lastReveal(), state.players().get(0)) + ","
                + choiceFor(state.lastReveal(), state.players().get(1)) + ";roundWinner="
                + (state.lastReveal().winner() == null ? "DRAW" : state.lastReveal().winner());
        return new RuleOutcome(true, Set.of(state.winner()), state.finishReason() + reveal);
    }

    @Override public Map<String, String> viewFor(RuleState raw, UUID playerId) {
        State state = state(raw);
        if (!state.players().contains(playerId)) throw new IllegalArgumentException("Unknown player");
        var view = new LinkedHashMap<String, String>();
        view.put("round", Integer.toString(state.round()));
        view.put("roundsToWin", Integer.toString(state.roundsToWin()));
        state.players().forEach(player -> view.put("score." + player, Integer.toString(state.scores().get(player))));
        state.players().forEach(player -> view.put("submitted." + player, Boolean.toString(state.choices().containsKey(player))));
        if (state.lastReveal() != null) {
            view.put("lastRound", Integer.toString(state.lastReveal().number()));
            state.players().forEach(player -> view.put("lastChoice." + player, choiceFor(state.lastReveal(), player)));
            view.put("lastRoundWinner", state.lastReveal().winner() == null ? "DRAW" : state.lastReveal().winner().toString());
        }
        view.put("yourChoice", state.choices().containsKey(playerId) ? state.choices().get(playerId).name() : "");
        return Map.copyOf(view);
    }

    private State resolve(State state, Map<UUID, Choice> choices) { return resolve(state, choices, roundWinner(state.players(), choices)); }

    private State resolve(State state, Map<UUID, Choice> choices, UUID roundWinner) {
        var scores = new LinkedHashMap<>(state.scores());
        if (roundWinner != null) scores.compute(roundWinner, (ignored, points) -> points + 1);
        var reveal = new RoundReveal(state.round(), choices, roundWinner);
        UUID matchWinner = roundWinner != null && scores.get(roundWinner) >= state.roundsToWin() ? roundWinner : null;
        String reason = matchWinner == null ? null : "RPS_WIN";
        return copy(state, scores, state.round() + 1, Map.of(), Set.of(), reveal, matchWinner, reason);
    }

    private UUID roundWinner(List<UUID> players, Map<UUID, Choice> choices) {
        if (choices.size() != 2) return null;
        Choice first = choices.get(players.get(0)), second = choices.get(players.get(1));
        if (first == second) return null;
        boolean firstWins = first == Choice.ROCK && second == Choice.SCISSORS
                || first == Choice.SCISSORS && second == Choice.PAPER
                || first == Choice.PAPER && second == Choice.ROCK;
        return firstWins ? players.get(0) : players.get(1);
    }

    private String choiceFor(RoundReveal reveal, UUID player) {
        Choice choice = reveal.choices().get(player);
        return choice == null ? "TIMEOUT" : choice.name();
    }

    private Choice parseChoice(String value) {
        if (value == null) return null;
        try { return Choice.valueOf(value); }
        catch (IllegalArgumentException ex) { return null; }
    }

    private State copy(State source, Map<UUID, Integer> scores, int round, Map<UUID, Choice> choices,
                       Set<UUID> timedOut, RoundReveal reveal, UUID winner, String reason) {
        return new State(source.players(), scores, source.roundsToWin(), round, choices, timedOut, reveal, winner, reason);
    }

    private State state(RuleState state) {
        if (!(state instanceof State value)) throw new IllegalArgumentException("State does not belong to Rock-Paper-Scissors");
        return value;
    }
}
