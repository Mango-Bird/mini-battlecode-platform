package dev.battlecode.engine;
import dev.battlecode.rulesapi.RuleState;
import java.util.UUID;
import java.util.Objects;
public record GameState(UUID matchId, MatchStatus status, int turn, RuleState ruleState) {
    public GameState {
        Objects.requireNonNull(matchId); Objects.requireNonNull(status); Objects.requireNonNull(ruleState);
        if (turn < 0) throw new IllegalArgumentException("Negative turn");
    }
}
