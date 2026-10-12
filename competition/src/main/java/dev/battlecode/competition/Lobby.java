package dev.battlecode.competition;
import dev.battlecode.rulesapi.GameConfig;
import java.util.*;
public interface Lobby {
    PlayerSession register(UUID sessionId, String name);
    Room createRoom(UUID sessionId, String ruleId, GameConfig config);
    Room joinRoom(UUID sessionId, UUID roomId);
    void leaveRoom(UUID sessionId);
    List<Room> rooms();
    void disconnect(UUID sessionId);
    /** Atomic snapshot of the requesting member's own room; LOBBY returns empty, invalid sessions fail. */
    default Optional<RoomDetails> roomDetails(UUID sessionId) { throw new UnsupportedOperationException(); }
}
