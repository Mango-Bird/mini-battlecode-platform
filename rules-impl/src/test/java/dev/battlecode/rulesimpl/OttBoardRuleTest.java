package dev.battlecode.rulesimpl;
import dev.battlecode.rulesapi.*;
import dev.battlecode.rulesimpl.board.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class OttBoardRuleTest {
    final OttBoardRule rule=new OttBoardRule();final UUID a=UUID.randomUUID(),b=UUID.randomUUID();
    GameConfig config(){return new GameConfig(2,200,1000,Map.of());}
    RuleState start(){return rule.initialState(config(),List.of(a,b));}
    Map<String,String> move(String id,int row,int col){return Map.of("pieceId",id,"to.row",""+row,"to.col",""+col);}
    @Test void requiresTwoPlayersAndAtLeastTwoHundredEngineTurns() {
        assertDoesNotThrow(()->rule.validateConfig(config()));
        assertThrows(IllegalArgumentException.class,()->rule.validateConfig(new GameConfig(2,199,1000,Map.of())));
        assertThrows(IllegalArgumentException.class,()->rule.validateConfig(new GameConfig(3,200,1000,Map.of())));
        assertThrows(IllegalArgumentException.class,()->rule.validateConfig(new GameConfig(2,200,1000,Map.of("layout","other"))));
        assertThrows(IllegalArgumentException.class,()->rule.initialState(config(),List.of(a,a)));
    }
    @Test void identitiesMapToIndependentSidesAndTurnsStartOne() {
        var state=start();assertEquals(a,rule.currentPlayer(state));assertEquals(Set.of(a),rule.activePlayers(state));
        assertEquals("A",rule.observation(state,a,900).playerId());assertEquals("B",rule.observation(state,b,900).playerId());
        assertEquals(1,rule.observation(state,a,900).turn());assertTrue(rule.observation(state,b,900).legalMoves().isEmpty());
        assertThrows(IllegalArgumentException.class,()->rule.observation(state,UUID.randomUUID(),900));
    }
    @Test void validMovementAlternatesAndHistoryIsValidated() {
        var state=start();state=rule.applyAction(state,a,move("A-S2",2,2));
        assertEquals(b,rule.currentPlayer(state));assertEquals(2,rule.observation(state,b,350).turn());
        assertEquals(1,rule.history(state).size());assertEquals("A-S2",rule.history(state).getFirst().pieceId());
    }
    @Test void activeSubmissionIsAdjudicatedAndInvalidMoveForfeits() {
        var before=start();assertTrue(rule.isActionAllowed(before,a,Map.of()));
        var after=rule.applyAction(before,a,move("A-R1",0,1));
        assertEquals(new RuleOutcome(true,Set.of(b),"FORFEIT"),rule.evaluate(after));
        assertEquals("0",rule.viewFor(after,a).get("validMoveCount"));assertTrue(rule.history(after).isEmpty());
    }
    @Test void malformedStringMapsForfeit() {
        for(var action:List.of(Map.<String,String>of(),Map.of("pieceId","A-S2","to.row","2.0","to.col","2"),
            Map.of("pieceId","A-S2","to.row","2","to.col","2","extra","x"),
            Map.of("pieceId","A-S2","to.row","2147483648","to.col","2"),
            Map.of("pieceId","A-S2","to.row","-1","to.col","2"))) {
            assertEquals("FORFEIT",rule.evaluate(rule.applyAction(start(),a,action)).reason());
        }
    }
    @Test void timeoutForfeitsActiveSideWithoutMoves() {
        var state=rule.onTurnTimeout(start(),a);
        assertEquals(Set.of(b),rule.evaluate(state).winners());assertEquals("FORFEIT",rule.evaluate(state).reason());
        assertTrue(rule.history(state).isEmpty());assertFalse(rule.evaluate(rule.onTurnTimeout(start(),b)).finished());
    }
    @Test void disconnectForfeitsEitherSide() {
        assertEquals(Set.of(b),rule.evaluate(rule.onDisconnect(start(),a)).winners());
        assertEquals(Set.of(a),rule.evaluate(rule.onDisconnect(start(),b)).winners());
    }
    @Test void inactiveActionsRemainRejectedAndTerminalIsIdempotent() {
        var state=start();assertFalse(rule.isActionAllowed(state,b,move("B-R1",6,6)));
        assertThrows(IllegalArgumentException.class,()->rule.applyAction(state,b,move("B-R1",6,6)));
        var terminal=rule.onTurnTimeout(state,a);assertSame(terminal,rule.onTurnTimeout(terminal,a));
        assertSame(terminal,rule.onDisconnect(terminal,b));
    }
    @Test void compactViewHasNoIdentityOrInventedBudgetAndFitsLimits() {
        var view=rule.viewFor(start(),a);
        assertEquals("1",view.get("turn"));assertEquals("A",view.get("playerId"));
        assertFalse(view.containsKey("remainingTimeMs"));assertTrue(view.size()<120);
        assertTrue(view.values().stream().noneMatch(v->v.contains(a.toString())||v.contains(b.toString())));
        assertTrue(view.values().stream().mapToInt(String::length).sum()<60000);
    }
    @Test void independentMatchesShareNoMutableState() {
        var first=start();var second=start();rule.applyAction(first,a,move("A-S2",2,2));
        assertEquals("0",rule.viewFor(second,a).get("validMoveCount"));assertTrue(rule.history(second).isEmpty());
    }
}
