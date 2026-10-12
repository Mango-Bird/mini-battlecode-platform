package dev.battlecode.rulesapi;
import java.util.*;
public final class InMemoryRuleRegistry implements RuleRegistry {
    private final Map<String,GameRule> rules;
    public InMemoryRuleRegistry(Collection<? extends GameRule> implementations) {
        var indexed = new HashMap<String,GameRule>();
        for (var rule : implementations) {
            if (rule.id() == null || rule.id().isBlank()) throw new IllegalArgumentException("Blank rule id");
            if (indexed.putIfAbsent(rule.id(), rule) != null) throw new IllegalArgumentException("Duplicate rule id");
        }
        rules = Map.copyOf(indexed);
    }
    public Optional<GameRule> find(String id) { return Optional.ofNullable(rules.get(id)); }
    public Set<String> ruleIds() { return rules.keySet(); }
}
