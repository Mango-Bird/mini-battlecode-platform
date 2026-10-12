package dev.battlecode.competition;
import dev.battlecode.rulesapi.GameConfig;
import java.util.*;
/** Immutable snapshot; mutations must be serialized by the future lobby implementation. */
public record Room(UUID roomId, UUID ownerId, String ruleId, GameConfig config, RoomStatus status, Set<UUID> players) {
    public Room {
        Objects.requireNonNull(roomId); Objects.requireNonNull(ownerId); Objects.requireNonNull(config); Objects.requireNonNull(status);
        if (ruleId == null || ruleId.isBlank()) throw new IllegalArgumentException("Blank rule id");
        players = Set.copyOf(players);
        if (!players.contains(ownerId) || players.size() > config.maxPlayers()) throw new IllegalArgumentException("Invalid room membership");
    }
}
