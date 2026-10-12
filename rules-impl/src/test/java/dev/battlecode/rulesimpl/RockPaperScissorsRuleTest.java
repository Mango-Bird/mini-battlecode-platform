package dev.battlecode.rulesimpl;

import dev.battlecode.rulesapi.GameConfig;
import dev.battlecode.rulesapi.RuleOutcome;
import dev.battlecode.rulesapi.RuleState;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RockPaperScissorsRuleTest {
    private final RockPaperScissorsRule rule = new RockPaperScissorsRule();
    private final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
    private GameConfig config(Map<String,String> options) { return new GameConfig(2, 100, 1000, options); }
    private RuleState initial(int target) { return rule.initialState(config(Map.of("roundsToWin", Integer.toString(target))), List.of(a,b)); }
    private RuleState choose(RuleState state, UUID player, String choice) { return rule.applyAction(state, player, Map.of("choice", choice)); }

    @Test void validatesOptionsAndExactlyTwoPlayers() {
        assertDoesNotThrow(() -> rule.validateConfig(config(Map.of())));
        assertDoesNotThrow(() -> rule.validateConfig(config(Map.of("roundsToWin", "10"))));
        assertThrows(IllegalArgumentException.class, () -> rule.validateConfig(new GameConfig(3, 10, 1000, Map.of())));
        for (String bad : List.of("0", "11", "three", "1.0"))
            assertThrows(IllegalArgumentException.class, () -> rule.validateConfig(config(Map.of("roundsToWin", bad))));
        assertThrows(IllegalArgumentException.class, () -> rule.validateConfig(config(Map.of("unknown", "1"))));
        assertThrows(IllegalArgumentException.class, () -> rule.initialState(config(Map.of()), List.of(a,a)));
    }

    @Test void initialStateKeepsRosterAndDefaultTarget() {
        RuleState state = rule.initialState(config(Map.of()), List.of(a,b));
        assertEquals(a, rule.currentPlayer(state));
        assertEquals("1", rule.viewFor(state,a).get("round"));
        assertEquals("3", rule.viewFor(state,a).get("roundsToWin"));
        assertEquals("0", rule.viewFor(state,a).get("score." + a));
        assertEquals(Set.of(a,b), rule.activePlayers(state));
    }

    @Test void allThreeWinningRelationshipsScoreCorrectly() {
        for (String[] pair : List.of(new String[]{"ROCK","SCISSORS"}, new String[]{"SCISSORS","PAPER"}, new String[]{"PAPER","ROCK"})) {
            RuleState start = initial(2);
            RuleState first = choose(start,a,pair[0]);
            assertFalse(rule.isTurnComplete(start, first));
            assertEquals("true", rule.viewFor(first,b).get("submitted." + a));
            assertFalse(rule.viewFor(first,b).containsValue(pair[0]));
            RuleState result = choose(first,b,pair[1]);
            assertTrue(rule.isTurnComplete(first,result));
            assertEquals("1", rule.viewFor(result,a).get("score." + a));
            assertEquals(a.toString(), rule.viewFor(result,b).get("lastRoundWinner"));
            assertEquals("false", rule.viewFor(result,a).get("submitted." + a));
        }
    }

    @Test void roundsDrawDoNotScoreAndChoicesRevealOnlyAfterBothSubmit() {
        RuleState state = initial(2);
        RuleState first = choose(state,a,"ROCK");
        assertEquals("ROCK", rule.viewFor(first,a).get("yourChoice"));
        assertEquals("", rule.viewFor(first,b).get("yourChoice"));
        assertFalse(rule.viewFor(first,b).values().contains("ROCK"));
        RuleState draw = choose(first,b,"ROCK");
        assertEquals("0", rule.viewFor(draw,a).get("score." + a));
        assertEquals("ROCK", rule.viewFor(draw,a).get("lastChoice." + a));
        assertEquals("ROCK", rule.viewFor(draw,b).get("lastChoice." + b));
        assertEquals("DRAW", rule.viewFor(draw,a).get("lastRoundWinner"));
    }

    @Test void onlyOneSubmitPerRoundAndInvalidChoiceIsRejected() {
        RuleState first = choose(initial(2),a,"PAPER");
        assertFalse(rule.isActionAllowed(first,a,Map.of("choice","ROCK")));
        assertFalse(rule.isActionAllowed(first,b,Map.of("choice","LIZARD")));
        assertFalse(rule.isActionAllowed(first,b,Map.of("choice","ROCK","extra","x")));
        assertThrows(IllegalArgumentException.class, () -> choose(first,a,"ROCK"));
    }

    @Test void firstToTargetFinishesAndPublishesFinalRoundInResultReason() {
        RuleState state = initial(1);
        state = choose(state,a,"PAPER");
        state = choose(state,b,"ROCK");
        RuleOutcome outcome = rule.evaluate(state);
        assertTrue(outcome.finished());
        assertEquals(Set.of(a), outcome.winners());
        assertTrue(outcome.reason().contains("RPS_WIN;round=1;choices=PAPER,ROCK;roundWinner=" + a));
    }

    @Test void oneSubmitterWinsRoundOnTimeoutAndBothMissingDraw() {
        RuleState submitted = choose(initial(3),a,"SCISSORS");
        RuleState oneTimeout = rule.onTurnTimeout(submitted,b);
        assertEquals("1", rule.viewFor(oneTimeout,a).get("score." + a));
        assertEquals("SCISSORS", rule.viewFor(oneTimeout,b).get("lastChoice." + a));
        assertEquals("TIMEOUT", rule.viewFor(oneTimeout,b).get("lastChoice." + b));

        RuleState start = initial(3);
        RuleState timeoutA = rule.onTurnTimeout(start,a);
        assertEquals("0", rule.viewFor(timeoutA,a).get("score." + a));
        RuleState timeoutB = rule.onTurnTimeout(timeoutA,b);
        assertEquals("0", rule.viewFor(timeoutB,a).get("score." + a));
        assertEquals("DRAW", rule.viewFor(timeoutB,b).get("lastRoundWinner"));
    }

    @Test void disconnectForfeitsAndMatchStatesAreIsolated() {
        RuleState firstMatch = initial(1);
        RuleState secondMatch = rule.initialState(config(Map.of("roundsToWin","2")), List.of(UUID.randomUUID(),UUID.randomUUID()));
        RuleState finished = rule.onDisconnect(firstMatch,a);
        assertEquals(Set.of(b), rule.evaluate(finished).winners());
        assertEquals("FORFEIT_DISCONNECT", rule.evaluate(finished).reason());
        UUID otherFirst = rule.currentPlayer(secondMatch);
        assertEquals("1", rule.viewFor(secondMatch, otherFirst).get("round"));
        assertEquals("0", rule.viewFor(secondMatch, otherFirst).get("score." + otherFirst));
    }
}
