package dev.battlecode.rulesapi;
import java.util.Map;
public record GameConfig(int maxPlayers, int maxTurns, long turnTimeoutMillis, Map<String,String> options) {
    public GameConfig {
        if (maxPlayers < 2 || maxPlayers > 64 || maxTurns < 1 || turnTimeoutMillis < 100 || turnTimeoutMillis > 300_000)
            throw new IllegalArgumentException("Invalid game limits");
        options = Map.copyOf(options);
    }
}
