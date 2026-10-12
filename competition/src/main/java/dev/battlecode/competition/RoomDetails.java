package dev.battlecode.competition;

import java.util.*;

/** Member-authorized public projection; contains no config secrets, rule state or transport metadata. */
public record RoomDetails(UUID roomId, String ruleId, RoomStatus status, UUID hostId,
                          List<Player> players, int capacity, long revision) {
    public record Player(UUID playerId, String nickname) {
        public Player { Objects.requireNonNull(playerId); Objects.requireNonNull(nickname); }
    }
    public RoomDetails {
        Objects.requireNonNull(roomId); Objects.requireNonNull(ruleId);
        Objects.requireNonNull(status); Objects.requireNonNull(hostId);
        players = List.copyOf(players);
        if (capacity < 2 || players.size() > capacity || revision < 1) throw new IllegalArgumentException("Invalid room projection");
    }
    public boolean contains(UUID playerId) { return players.stream().anyMatch(player -> player.playerId().equals(playerId)); }
}
