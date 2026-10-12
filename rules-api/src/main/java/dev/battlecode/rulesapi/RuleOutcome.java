package dev.battlecode.rulesapi;
import java.util.Set;
import java.util.UUID;
public record RuleOutcome(boolean finished, Set<UUID> winners, String reason) {
    public RuleOutcome {
        winners = Set.copyOf(winners);
        java.util.Objects.requireNonNull(reason);
        if (!finished && !winners.isEmpty()) throw new IllegalArgumentException("Unfinished outcome has winners");
    }
}
