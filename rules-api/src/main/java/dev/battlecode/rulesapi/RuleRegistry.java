package dev.battlecode.rulesapi;
import java.util.Optional;
import java.util.Set;
public interface RuleRegistry {
    Optional<GameRule> find(String ruleId);
    Set<String> ruleIds();
}
