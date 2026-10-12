package dev.battlecode.rulesapi;
import java.util.List;
import java.util.Map;
import java.util.UUID;
/**
 * Server-side trusted implementation; never loads client source code.
 * Implementations must be stateless/reentrant across matches and return immutable RuleState.
 * Callbacks must terminate promptly; viewFor is responsible for hiding rule-specific secrets.
 */
public interface GameRule {
    /** Descriptor-only registrations override this to false. */
    default boolean supportsGameplay() { return true; }
    default int minimumPlayers(GameConfig config) { return 2; }
    /** Simultaneous rules return all currently eligible players. */
    default java.util.Set<UUID> activePlayers(RuleState state) { return java.util.Set.of(currentPlayer(state)); }
    /**
     * Called after action/disconnect. False keeps the shared turn and deadline.
     * A player may submit only once per turn; simultaneous rules override this and activePlayers.
     * Timeout closes the expired turn after callbacks for pending active players.
     */
    default boolean isTurnComplete(RuleState before, RuleState after) { return true; }
    String id();
    void validateConfig(GameConfig config);
    RuleState initialState(GameConfig config, List<UUID> players);
    UUID currentPlayer(RuleState state);
    boolean isActionAllowed(RuleState state, UUID playerId, Map<String,String> action);
    RuleState applyAction(RuleState state, UUID playerId, Map<String,String> action);
    RuleState onTurnTimeout(RuleState state, UUID playerId);
    RuleState onDisconnect(RuleState state, UUID playerId);
    RuleOutcome evaluate(RuleState state);
    Map<String,String> viewFor(RuleState state, UUID playerId);
}
