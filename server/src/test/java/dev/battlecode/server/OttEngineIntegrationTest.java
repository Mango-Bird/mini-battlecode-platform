package dev.battlecode.server;
import dev.battlecode.engine.*;
import dev.battlecode.rulesapi.*;
import dev.battlecode.rulesimpl.*;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
@Timeout(10)
class OttEngineIntegrationTest {
    final OttBoardRule rule=new OttBoardRule();final UUID a=UUID.randomUUID(),b=UUID.randomUUID(),id=UUID.randomUUID();
    final DefaultGameEngine engine=new DefaultGameEngine();
    void start(int millis){engine.start(id,rule,new GameConfig(2,200,millis,Map.of()),List.of(a,b));}
    Map<String,String> move(String piece,int row,int col){return Map.of("pieceId",piece,"to.row",""+row,"to.col",""+col);}
    @Test void engineTurnZeroMapsToBotTurnOneAndViewsStayBounded() {
        start(1000);var snapshot=engine.snapshot(id,a);
        assertEquals(0,snapshot.turn());assertEquals("1",snapshot.view().get("turn"));assertEquals("A",snapshot.view().get("playerId"));
        assertFalse(snapshot.view().containsKey("remainingTimeMs"));
        var state=engine.submit(id,a,0,move("A-S2",2,2));
        assertEquals(1,state.turn());assertEquals("2",engine.snapshot(id,b).view().get("turn"));
        assertEquals(1,rule.history(state.ruleState()).size());
    }
    @Test void staleOutOfTurnAndOutsiderKeepExistingErrorsWithoutForfeit() {
        start(1000);
        for(var call:List.<Runnable>of(()->engine.submit(id,a,1,move("A-S2",2,2)),
            ()->engine.submit(id,b,0,move("B-R1",6,6)),()->engine.submit(id,UUID.randomUUID(),0,move("A-S2",2,2))))
            assertThrows(EngineException.class,call::run);
        assertEquals(MatchStatus.RUNNING,engine.snapshot(id,a).status());assertEquals(0,engine.snapshot(id,a).turn());
        assertTrue(engine.result(id).isEmpty());
    }
    @Test void invalidActiveActionFinishesAsForfeitNotRuleErrorAndCannotRetry() {
        start(1000);var state=engine.submit(id,a,0,Map.of("pieceId","A-R1","to.row","0","to.col","1","extra","x"));
        assertEquals(MatchStatus.FINISHED,state.status());assertEquals("FORFEIT",engine.result(id).orElseThrow().reason());
        assertEquals(Set.of(b),engine.result(id).orElseThrow().winners());assertTrue(rule.history(state.ruleState()).isEmpty());
        assertThrows(EngineException.class,()->engine.submit(id,a,1,move("A-S2",2,2)));
    }
    @Test void drawAtTwoHundredTakesPrecedenceOverEngineMaxTurns() {
        start(1000);GameState state=null;
        for(int n=0;n<200;n++)state=engine.submit(id,n%2==0?a:b,n,n%2==0
            ?move("A-S2",n%4==0?2:1,2):move("B-R1",n%4==1?6:7,6));
        assertEquals(MatchStatus.FINISHED,state.status());assertEquals(200,state.turn());
        assertEquals("MOVE_LIMIT_REACHED",engine.result(id).orElseThrow().reason());
        assertTrue(engine.result(id).orElseThrow().winners().isEmpty());assertEquals(200,rule.history(state.ruleState()).size());
    }
    @Test void timeoutUsesGenerationAndForfeitsWithoutMove() throws Exception {
        start(100);long generation=engine.snapshot(id,a).generation();
        assertEquals(MatchStatus.RUNNING,engine.timeout(id,generation-1).status());
        Thread.sleep(130);
        var state=engine.timeout(id,generation);
        assertEquals(MatchStatus.FINISHED,state.status());assertEquals(Set.of(b),engine.result(id).orElseThrow().winners());
        assertEquals("FORFEIT",engine.result(id).orElseThrow().reason());assertTrue(rule.history(state.ruleState()).isEmpty());
    }
    @Test void concurrentActionCommitsOnceAndDoesNotForfeitStaleRequest() throws Exception {
        start(3000);
        var gate=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)) {
            Callable<Boolean> action=()->{if(!gate.await(2,TimeUnit.SECONDS))throw new TimeoutException();
                try{engine.submit(id,a,0,move("A-S2",2,2));return true;}catch(EngineException ex){return false;}};
            var one=workers.submit(action);var two=workers.submit(action);gate.countDown();
            assertNotEquals(one.get(2,TimeUnit.SECONDS),two.get(2,TimeUnit.SECONDS));
        }
        assertEquals(1,engine.snapshot(id,b).turn());assertTrue(engine.result(id).isEmpty());
    }
    @Test void disconnectForfeitsAndOtherRuleMatchIsUnaffected() {
        start(1000);UUID other=UUID.randomUUID();
        engine.start(other,new RockPaperScissorsRule(),new GameConfig(2,200,1000,Map.of()),List.of(a,b));
        engine.disconnect(id,b);assertEquals(Set.of(a),engine.result(id).orElseThrow().winners());
        assertEquals("FORFEIT",engine.result(id).orElseThrow().reason());
        assertEquals(MatchStatus.RUNNING,engine.snapshot(other,a).status());assertTrue(engine.result(other).isEmpty());
    }
}
