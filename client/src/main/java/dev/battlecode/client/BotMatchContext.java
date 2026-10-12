package dev.battlecode.client;

import java.util.Map;
import java.util.Objects;

/** Immutable client-side information projected by the existing protocol. */
public record BotMatchContext(String ruleId, String sessionId, String matchId, int turn,
                              Map<String, String> playerView) {
    public BotMatchContext {
        Objects.requireNonNull(ruleId);
        Objects.requireNonNull(sessionId);
        Objects.requireNonNull(matchId);
        if (turn < 0) throw new IllegalArgumentException("turn must be non-negative");
        playerView = Map.copyOf(playerView);
    }
}
