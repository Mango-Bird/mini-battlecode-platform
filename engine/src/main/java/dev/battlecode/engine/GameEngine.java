package dev.battlecode.engine;
import dev.battlecode.rulesapi.*;
import java.util.*;
/** Implementations serialize actions per match and validate membership, turn and deadline before rule dispatch. */
public interface GameEngine {
    GameState start(UUID matchId, GameRule rule, GameConfig config, List<UUID> players);
    GameState submit(UUID matchId, UUID playerId, Map<String,String> action);
    GameState timeout(UUID matchId, UUID playerId);
    GameState disconnect(UUID matchId, UUID playerId);
    Optional<GameResult> result(UUID matchId);
    void cancel(UUID matchId, String reason);
    /** expectedTurn >= 0, checked atomically with apply. TCP adapters must use this overload. */
    default GameState submit(UUID matchId, UUID playerId, int expectedTurn, Map<String,String> action) {
        throw new UnsupportedOperationException();
    }
    /** Only expires the matching generation at/after its monotonic deadline; stale calls are no-ops. */
    default GameState timeout(UUID matchId, long generation) { throw new UnsupportedOperationException(); }
    /** Private immutable projection; no RuleState or other players' views. */
    default PlayerSnapshot snapshot(UUID matchId, UUID playerId) { throw new UnsupportedOperationException(); }
}
