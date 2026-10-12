package dev.battlecode.rulesapi;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class GameConfigTest {
    @Test void validatesLimits() {
        assertThrows(IllegalArgumentException.class, () -> new GameConfig(1, 10, 1000, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new GameConfig(2, 0, 1000, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new GameConfig(2, 10, 0, Map.of()));
    }
    @Test void configCopiesOptions() {
        var options = new HashMap<String,String>(); options.put("seed", "42");
        var config = new GameConfig(2, 10, 1000, options); options.clear();
        assertEquals("42", config.options().get("seed")); assertThrows(UnsupportedOperationException.class, () -> config.options().clear());
    }
    @Test void emptyRegistryAndOutcomeConstraints() {
        var registry = new InMemoryRuleRegistry(List.of()); assertTrue(registry.ruleIds().isEmpty()); assertTrue(registry.find("missing").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new RuleOutcome(false, Set.of(UUID.randomUUID()), "pending"));
    }
}
