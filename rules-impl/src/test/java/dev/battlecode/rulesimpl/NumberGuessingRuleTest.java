package dev.battlecode.rulesimpl;

import dev.battlecode.rulesapi.GameConfig;
import dev.battlecode.rulesapi.RuleState;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class NumberGuessingRuleTest {
    private final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
    private GameConfig config(int maxTurns, Map<String,String> options) { return new GameConfig(2,maxTurns,1000,options); }
    private NumberGuessingRule deterministic(int offset) { return new NumberGuessingRule(bound -> offset); }
    private RuleState start(NumberGuessingRule rule, GameConfig config) { return rule.initialState(config,List.of(a,b)); }
    private RuleState guess(NumberGuessingRule rule, RuleState state, UUID player, String value) {
        return rule.applyAction(state,player,Map.of("guess",value));
    }

    @Test void validatesRangeDefaultsAndExactlyTwoPlayers() {
        var rule = deterministic(0);
        assertDoesNotThrow(() -> rule.validateConfig(config(10,Map.of())));
        assertDoesNotThrow(() -> rule.validateConfig(config(10,Map.of("minNumber","-20","maxNumber","20"))));
        assertThrows(IllegalArgumentException.class, () -> rule.validateConfig(new GameConfig(3,10,1000,Map.of())));
        for (Map<String,String> options : List.of(Map.of("minNumber","4","maxNumber","4"),
                Map.of("minNumber","2147483647","maxNumber","-2147483648"),
                Map.of("minNumber","0","maxNumber","1000000"), Map.of("minNumber","x"), Map.of("seed","1")))
            assertThrows(IllegalArgumentException.class, () -> rule.validateConfig(config(10,options)));
        assertThrows(IllegalArgumentException.class, () -> rule.initialState(config(10,Map.of()),List.of(a,a)));
    }

    @Test void defaultAndConfiguredSecretsAreInRangeAndRandomInjectionIsDeterministic() {
        var defaultState = start(deterministic(99),config(10,Map.of()));
        assertTrue(deterministic(0).evaluate(guess(deterministic(0),defaultState,a,"100")).reason().contains("secret=100"));
        var customRule = deterministic(2);
        var custom = start(customRule,config(10,Map.of("minNumber","-5","maxNumber","5")));
        assertTrue(customRule.evaluate(guess(customRule,custom,a,"-3")).reason().contains("secret=-3"));
        assertThrows(IllegalArgumentException.class, () -> start(deterministic(100),config(10,Map.of())));
    }

    @Test void lowerHigherAndCorrectGuessesReturnHintsAndWinner() {
        var rule = deterministic(41); // Secret 42.
        RuleState state = start(rule,config(10,Map.of()));
        assertEquals("HIGHER", rule.viewFor(guess(rule,state,a,"10"),b).get("history.0.hint"));
        state = guess(rule,state,a,"80");
        assertEquals("LOWER", rule.viewFor(state,a).get("history.0.hint"));
        assertEquals(b,rule.currentPlayer(state));
        RuleState finished = guess(rule,state,b,"42");
        assertTrue(rule.evaluate(finished).finished());
        assertEquals(Set.of(b),rule.evaluate(finished).winners());
        assertEquals("CORRECT_GUESS;secret=42",rule.evaluate(finished).reason());
    }

    @Test void turnOrderRangeAndIntegerPayloadAreEnforced() {
        var rule = deterministic(41);
        RuleState state = start(rule,config(10,Map.of()));
        assertFalse(rule.isActionAllowed(state,b,Map.of("guess","42")));
        for (String bad : List.of("0","101","42.0","+42"," 42","2147483648","abc"))
            assertFalse(rule.isActionAllowed(state,a,Map.of("guess",bad)));
        assertFalse(rule.isActionAllowed(state,a,Map.of("guess","42","extra","x")));
        assertFalse(rule.viewFor(state,a).containsValue("42"));
    }

    @Test void timeoutPassesTurnDisconnectForfeitsAndStateIsIsolated() {
        var rule = deterministic(10);
        RuleState state = start(rule,config(10,Map.of()));
        RuleState next = rule.onTurnTimeout(state,a);
        assertEquals(b,rule.currentPlayer(next));
        assertEquals("1",rule.viewFor(next,b).get("turnsTaken"));
        RuleState disconnected = rule.onDisconnect(next,b);
        assertEquals(Set.of(a),rule.evaluate(disconnected).winners());
        assertFalse(rule.viewFor(next,b).containsValue("41"));

        var otherRule = deterministic(99);
        RuleState other = start(otherRule,config(10,Map.of()));
        assertEquals("0",otherRule.viewFor(other,a).get("turnsTaken"));
        assertEquals(Set.of(),otherRule.evaluate(other).winners());
    }

    @Test void publicHistoryWindowStaysWithinEngineViewBudget() {
        var rule=deterministic(41);RuleState state=start(rule,config(100,Map.of()));
        for(int i=0;i<35;i++) state=guess(rule,state,rule.currentPlayer(state),"1");
        Map<String,String> view=rule.viewFor(state,a);
        assertEquals("28",view.get("historyCount"));
        assertEquals("8",view.get("history.0.turn"));
        assertEquals("35",view.get("history.27.turn"));
        assertEquals(119,view.size());
    }
}
