package dev.battlecode.rulesimpl;

import dev.battlecode.rulesapi.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RoomOnlyRuleTest {
    @Test void registersDescriptorAndValidatesRoomConfig() {
        var rule = new RoomOnlyRule();
        assertTrue(new InMemoryRuleRegistry(List.of(rule)).find(RoomOnlyRule.ID).isPresent());
        assertDoesNotThrow(() -> rule.validateConfig(new GameConfig(2, 10, 1000, Map.of())));
        assertThrows(IllegalArgumentException.class, () -> rule.validateConfig(new GameConfig(2, 10, 1000, Map.of("unknown", "value"))));
    }
    @Test void neverPretendsToSupportGameplay() {
        var rule = new RoomOnlyRule(); var id = UUID.randomUUID();
        assertThrows(UnsupportedOperationException.class, () -> rule.initialState(new GameConfig(2, 10, 1000, Map.of()), List.of(id)));
        assertThrows(UnsupportedOperationException.class, () -> rule.currentPlayer(null));
        assertThrows(UnsupportedOperationException.class, () -> rule.isActionAllowed(null, id, Map.of()));
        assertThrows(UnsupportedOperationException.class, () -> rule.applyAction(null, id, Map.of()));
        assertThrows(UnsupportedOperationException.class, () -> rule.onTurnTimeout(null, id));
        assertThrows(UnsupportedOperationException.class, () -> rule.onDisconnect(null, id));
        assertThrows(UnsupportedOperationException.class, () -> rule.evaluate(null));
        assertThrows(UnsupportedOperationException.class, () -> rule.viewFor(null, id));
    }
}
