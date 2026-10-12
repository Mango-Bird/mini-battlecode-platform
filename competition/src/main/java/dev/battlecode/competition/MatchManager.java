package dev.battlecode.competition;
import dev.battlecode.engine.*;
import java.util.*;
public interface MatchManager {
    UUID startMatch(UUID requestingSessionId, UUID roomId);
    GameState submitAction(UUID sessionId, UUID matchId, Map<String,String> action);
    void disconnect(UUID sessionId);
    Optional<GameResult> result(UUID matchId);
    default GameState submitAction(UUID sessionId, UUID matchId, int expectedTurn, Map<String,String> action) { throw new UnsupportedOperationException(); }
    default void shutdown() { throw new UnsupportedOperationException(); }
}
