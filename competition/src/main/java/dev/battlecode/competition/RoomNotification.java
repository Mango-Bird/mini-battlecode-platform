package dev.battlecode.competition;

import java.util.*;

/** Immutable domain event, with no socket/protocol dependency. Recipients are server-side session IDs. */
public record RoomNotification(String type, UUID roomId, RoomStatus status, int players, Set<UUID> recipients, RoomDetails details) {
    public RoomNotification(String type, UUID roomId, RoomStatus status, int players, Set<UUID> recipients) {
        this(type, roomId, status, players, recipients, null);
    }
    public RoomNotification { recipients = Set.copyOf(recipients); }
    // Optional web projection does not change the identity of legacy domain events.
    @Override public boolean equals(Object value) {
        return value instanceof RoomNotification other && players == other.players
            && Objects.equals(type, other.type) && Objects.equals(roomId, other.roomId)
            && status == other.status && recipients.equals(other.recipients);
    }
    @Override public int hashCode() { return Objects.hash(type, roomId, status, players, recipients); }
}
