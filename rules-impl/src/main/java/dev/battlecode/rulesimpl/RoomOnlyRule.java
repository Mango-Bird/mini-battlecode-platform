package dev.battlecode.rulesimpl;

import dev.battlecode.rulesapi.*;
import java.util.*;

/** Configuration descriptor only. This registration deliberately cannot run a game. */
public final class RoomOnlyRule implements GameRule {
    public static final String ID = "room-only-no-gameplay";
    public String id() { return ID; }
    public boolean supportsGameplay() { return false; }
    public void validateConfig(GameConfig config) {
        Objects.requireNonNull(config);
        if (!config.options().isEmpty()) throw new IllegalArgumentException("Room-only descriptor accepts no rule options");
    }
    private UnsupportedOperationException unavailable() { return new UnsupportedOperationException("Room-only descriptor has no gameplay runtime"); }
    public RuleState initialState(GameConfig config, List<UUID> players) { throw unavailable(); }
    public UUID currentPlayer(RuleState state) { throw unavailable(); }
    public boolean isActionAllowed(RuleState state, UUID player, Map<String,String> action) { throw unavailable(); }
    public RuleState applyAction(RuleState state, UUID player, Map<String,String> action) { throw unavailable(); }
    public RuleState onTurnTimeout(RuleState state, UUID player) { throw unavailable(); }
    public RuleState onDisconnect(RuleState state, UUID player) { throw unavailable(); }
    public RuleOutcome evaluate(RuleState state) { throw unavailable(); }
    public Map<String,String> viewFor(RuleState state, UUID player) { throw unavailable(); }
}
