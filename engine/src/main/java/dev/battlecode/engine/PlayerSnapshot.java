package dev.battlecode.engine;

import java.util.*;

/** Contains only the requesting player's view, never the rule-owned state. */
public record PlayerSnapshot(UUID matchId, MatchStatus status, int turn, long generation,
                             long deadlineEpochMillis, Map<String,String> view) {
    public PlayerSnapshot { Objects.requireNonNull(matchId); Objects.requireNonNull(status); view = Map.copyOf(view); }
}
