package dev.battlecode.engine;
import java.util.*;
public record GameResult(UUID matchId, Set<UUID> winners, String reason) {
    public GameResult { Objects.requireNonNull(matchId); winners = Set.copyOf(winners); Objects.requireNonNull(reason); }
}
