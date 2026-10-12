package dev.battlecode.engine;

import dev.battlecode.rulesapi.*;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(8)
class DefaultGameEngineTest {
    private final UUID a=UUID.randomUUID(),b=UUID.randomUUID(),id=UUID.randomUUID();
    private final DefaultGameEngine engine=new DefaultGameEngine();
    private final GameConfig config=new GameConfig(2,10,1000,Map.of());
    private Rule rule;
    record State(List<UUID> players,int count,Set<UUID> acted) implements RuleState {
        State { players=List.copyOf(players); acted=Set.copyOf(acted); }
    }
    static class Rule implements GameRule {
        final boolean simultaneous;
        int finishAt=4;
        boolean explode, oversized;
        CountDownLatch entered,release;
        Rule(boolean simultaneous){this.simultaneous=simultaneous;}
        public String id(){return "fake";}
        public void validateConfig(GameConfig config){}
        public RuleState initialState(GameConfig config,List<UUID> players){return new State(players,0,Set.of());}
        public UUID currentPlayer(RuleState raw){State s=(State)raw;return s.players().get(s.count()%s.players().size());}
        public Set<UUID> activePlayers(RuleState raw){return simultaneous?Set.copyOf(((State)raw).players()):GameRule.super.activePlayers(raw);}
        public boolean isTurnComplete(RuleState before,RuleState after){return !simultaneous || ((State)after).count()%2==0;}
        public boolean isActionAllowed(RuleState state,UUID player,Map<String,String> action){return action.equals(Map.of("move","ok"));}
        public RuleState applyAction(RuleState raw,UUID player,Map<String,String> action){
            if(entered!=null){entered.countDown();try{if(!release.await(2,TimeUnit.SECONDS))throw new IllegalStateException("Test barrier timed out");}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException(ex);}}
            if(explode)throw new IllegalStateException("private failure");
            State s=(State)raw; var acted=new HashSet<>(s.acted());acted.add(player);return new State(s.players(),s.count()+1,acted);
        }
        public RuleState onTurnTimeout(RuleState raw,UUID player){State s=(State)raw;return new State(s.players(),s.count()+1,s.acted());}
        public RuleState onDisconnect(RuleState raw,UUID player){return raw;}
        public RuleOutcome evaluate(RuleState raw){State s=(State)raw;return new RuleOutcome(s.count()>=finishAt,s.count()>=finishAt?Set.of(s.players().get(0)):Set.of(),"FAKE_RESULT");}
        public Map<String,String> viewFor(RuleState raw,UUID player){return Map.of("self",player.toString(),"count",oversized?"x".repeat(65000):Integer.toString(((State)raw).count()));}
    }
    private void start(boolean simultaneous){rule=new Rule(simultaneous);engine.start(id,rule,config,List.of(a,b));}
    private GameState act(UUID player,int turn){return engine.submit(id,player,turn,Map.of("move","ok"));}
    @Test void initializesRunningWithPrivateImmutableSnapshot(){start(false);var s=engine.snapshot(id,a);assertEquals(MatchStatus.RUNNING,s.status());assertEquals(0,s.turn());assertEquals(a.toString(),s.view().get("self"));assertFalse(s.view().containsValue(b.toString()));assertThrows(UnsupportedOperationException.class,()->s.view().put("x","y"));}
    @Test void rejectsInvalidActionWithoutChangingState(){start(false);assertEquals(EngineException.Reason.ACTION_REJECTED,assertThrows(EngineException.class,()->engine.submit(id,a,0,Map.of())).reason());assertEquals("0",engine.snapshot(id,a).view().get("count"));}
    @Test void rejectsWrongPlayer(){start(false);assertEquals(EngineException.Reason.NOT_YOUR_TURN,assertThrows(EngineException.class,()->act(b,0)).reason());}
    @Test void appliesSequentialActionsAndFinishes(){start(false);act(a,0);act(b,1);act(a,2);assertEquals(MatchStatus.FINISHED,act(b,3).status());assertEquals(Set.of(a),engine.result(id).orElseThrow().winners());}
    @Test void rejectsAfterFinish(){start(false);rule.finishAt=1;act(a,0);assertThrows(EngineException.class,()->act(b,1));}
    @Test void rejectsStaleExpectedTurn(){start(false);act(a,0);assertEquals(EngineException.Reason.INVALID_STATE,assertThrows(EngineException.class,()->act(b,0)).reason());assertEquals("1",engine.snapshot(id,b).view().get("count"));}
    @Test void simultaneousActionsShareTurnAndDeadline(){start(true);var before=engine.snapshot(id,a);act(a,0);var partial=engine.snapshot(id,b);assertEquals(0,partial.turn());assertEquals(before.generation(),partial.generation());assertEquals(before.deadlineEpochMillis(),partial.deadlineEpochMillis());assertEquals(1,act(b,0).turn());}
    @Test void simultaneousPlayerCannotSubmitTwice(){start(true);act(a,0);assertThrows(EngineException.class,()->act(a,0));assertEquals("1",engine.snapshot(id,a).view().get("count"));}
    @Test void concurrentSimultaneousActionsDoNotLoseUpdates()throws Exception{start(true);var states=race(()->act(a,0),()->act(b,0));assertEquals(2,states.size());assertEquals("2",engine.snapshot(id,a).view().get("count"));assertEquals(1,engine.snapshot(id,a).turn());}
    @Test void competingSamePlayerAppliesOnlyOnce()throws Exception{start(true);var results=race(()->accepted(a),()->accepted(a));assertEquals(1,results.stream().filter(Boolean::booleanValue).count());assertEquals("1",engine.snapshot(id,a).view().get("count"));}
    private boolean accepted(UUID player){try{act(player,0);return true;}catch(EngineException ex){return false;}}
    @Test void staleTimeoutCannotAffectNewTurn(){start(false);long token=engine.snapshot(id,a).generation();act(a,0);engine.timeout(id,token);assertEquals("1",engine.snapshot(id,a).view().get("count"));assertEquals(1,engine.snapshot(id,a).turn());}
    @Test void earlyTimeoutDoesNothing(){start(false);engine.timeout(id,engine.snapshot(id,a).generation());assertEquals(0,engine.snapshot(id,a).turn());}
    @Test void rejectsNegativeExpectedTurn(){start(false);assertThrows(EngineException.class,()->act(a,-1));assertEquals(0,engine.snapshot(id,a).turn());}
    @Test void rejectsDuplicateMatchId(){start(false);assertThrows(EngineException.class,()->engine.start(id,new Rule(false),config,List.of(a,b)));assertEquals(0,engine.snapshot(id,a).turn());}
    @Test void legacySubmitAndTimeoutMethodsRemainAvailable(){start(false);assertEquals(1,engine.submit(id,a,Map.of("move","ok")).turn());assertEquals(1,engine.timeout(id,b).turn());}
    @Test void initializationFailurePublishesNoMatch(){var bad=new Rule(false){public RuleState initialState(GameConfig c,List<UUID> p){throw new IllegalStateException("failure");}};assertEquals(EngineException.Reason.INTERNAL_ERROR,assertThrows(EngineException.class,()->engine.start(id,bad,config,List.of(a,b))).reason());assertTrue(engine.result(id).isEmpty());engine.start(id,new Rule(false),config,List.of(a,b));}
    @Test void ruleValidationExceptionCancels(){rule=new Rule(false){public boolean isActionAllowed(RuleState s,UUID p,Map<String,String> action){throw new IllegalStateException("failure");}};engine.start(id,rule,config,List.of(a,b));assertThrows(EngineException.class,()->act(a,0));assertEquals("RULE_ERROR",engine.result(id).orElseThrow().reason());}
    @Test void disconnectRuleExceptionCancels(){rule=new Rule(false){public RuleState onDisconnect(RuleState s,UUID p){throw new IllegalStateException("failure");}};engine.start(id,rule,config,List.of(a,b));assertEquals(MatchStatus.CANCELLED,engine.disconnect(id,a).status());assertEquals("RULE_ERROR",engine.result(id).orElseThrow().reason());}
    @Test void timeoutRuleExceptionCancels()throws Exception{rule=new Rule(false){public RuleState onTurnTimeout(RuleState s,UUID p){throw new IllegalStateException("failure");}};engine.start(id,rule,new GameConfig(2,10,100,Map.of()),List.of(a,b));awaitDeadline();assertEquals(MatchStatus.CANCELLED,engine.timeout(id,engine.snapshot(id,a).generation()).status());assertEquals("RULE_ERROR",engine.result(id).orElseThrow().reason());}
    @Test void ruleCannotNameUnknownWinner(){rule=new Rule(false){public RuleOutcome evaluate(RuleState raw){return ((State)raw).count()==0?new RuleOutcome(false,Set.of(),""):new RuleOutcome(true,Set.of(UUID.randomUUID()),"bad");}};engine.start(id,rule,config,List.of(a,b));assertThrows(EngineException.class,()->act(a,0));assertTrue(engine.result(id).orElseThrow().winners().isEmpty());}
    @Test void simultaneousTimeoutSkipsPlayersWhoAlreadyActed()throws Exception{rule=new Rule(true);engine.start(id,rule,new GameConfig(2,10,100,Map.of()),List.of(a,b));act(a,0);awaitDeadline();engine.timeout(id,engine.snapshot(id,a).generation());assertEquals("2",engine.snapshot(id,a).view().get("count"));assertEquals(1,engine.snapshot(id,a).turn());}
    @Test void timeoutStopsWhenRuleFinishes()throws Exception{rule=new Rule(true);rule.finishAt=1;engine.start(id,rule,new GameConfig(2,10,100,Map.of()),List.of(a,b));awaitDeadline();assertEquals(MatchStatus.FINISHED,engine.timeout(id,engine.snapshot(id,a).generation()).status());assertEquals("FAKE_RESULT",engine.result(id).orElseThrow().reason());}
    @Test void concurrentSequentialActionsWithSameTurnOnlyApplyOnce()throws Exception{start(false);var results=race(()->accepted(a),()->accepted(b));assertEquals(1,results.stream().filter(Boolean::booleanValue).count());assertEquals("1",engine.snapshot(id,a).view().get("count"));}
    @Test void staleExpiredTokenStillCannotAffectNewTurn()throws Exception{start(false);long token=engine.snapshot(id,a).generation();act(a,0);awaitDeadline();engine.timeout(id,token);assertEquals(1,engine.snapshot(id,a).turn());assertTrue(engine.result(id).isEmpty());engine.timeout(id,engine.snapshot(id,a).generation());assertEquals(2,engine.snapshot(id,a).turn());}
    @Test void lateActionRejectedAndTimeoutAdvances()throws Exception{rule=new Rule(false);engine.start(id,rule,new GameConfig(2,10,100,Map.of()),List.of(a,b));awaitDeadline();assertEquals(EngineException.Reason.TIMEOUT,assertThrows(EngineException.class,()->act(a,0)).reason());engine.timeout(id,engine.snapshot(id,a).generation());assertEquals(1,engine.snapshot(id,a).turn());}
    @Test void maxTurnsCancelsWithoutInventingWinner(){rule=new Rule(false);engine.start(id,rule,new GameConfig(2,1,1000,Map.of()),List.of(a,b));assertEquals(MatchStatus.CANCELLED,act(a,0).status());assertEquals("MAX_TURNS",engine.result(id).orElseThrow().reason());assertTrue(engine.result(id).orElseThrow().winners().isEmpty());}
    @Test void ruleFailureCancelsAndPreservesPriorState(){start(false);rule.explode=true;assertEquals(EngineException.Reason.INTERNAL_ERROR,assertThrows(EngineException.class,()->act(a,0)).reason());assertEquals("RULE_ERROR",engine.result(id).orElseThrow().reason());assertEquals(0,engine.snapshot(id,a).turn());}
    @Test void oversizedViewCancels(){start(false);rule.oversized=true;assertThrows(EngineException.class,()->act(a,0));assertEquals(MatchStatus.CANCELLED,engine.snapshot(id,a).status());}
    @Test void disconnectedPlayersCannotActAndLastDisconnectCancels(){start(true);engine.disconnect(id,a);assertThrows(EngineException.class,()->act(a,1));engine.disconnect(id,b);assertEquals("NO_CONNECTED_PLAYERS",engine.result(id).orElseThrow().reason());}
    @Test void cancellationIsTerminalAndIdempotent(){start(false);engine.cancel(id,"SERVER_SHUTDOWN");engine.cancel(id,"OTHER");assertEquals("SERVER_SHUTDOWN",engine.result(id).orElseThrow().reason());assertThrows(EngineException.class,()->act(a,0));}
    @Test void oneRuleFailureDoesNotAffectAnotherMatch(){start(false);UUID other=UUID.randomUUID();engine.start(other,new Rule(false),config,List.of(a,b));rule.explode=true;assertThrows(EngineException.class,()->act(a,0));assertEquals(MatchStatus.RUNNING,engine.submit(other,a,0,Map.of("move","ok")).status());}
    @Test void sharedRuleInstanceHasIndependentMatchStateAndConfig(){start(false);UUID other=UUID.randomUUID();engine.start(other,rule,new GameConfig(2,1,1000,Map.of()),List.of(a,b));act(a,0);assertEquals("0",engine.snapshot(other,a).view().get("count"));assertEquals(MatchStatus.CANCELLED,engine.submit(other,a,0,Map.of("move","ok")).status());assertEquals(MatchStatus.RUNNING,engine.snapshot(id,a).status());assertEquals("1",engine.snapshot(id,a).view().get("count"));}
    @Test void actionDisconnectRaceIsSerialized()throws Exception{start(false);rule.entered=new CountDownLatch(1);rule.release=new CountDownLatch(1);var executor=Executors.newVirtualThreadPerTaskExecutor();try{var action=executor.submit(()->act(a,0));assertTrue(rule.entered.await(2,TimeUnit.SECONDS));var disconnect=executor.submit(()->engine.disconnect(id,b));rule.release.countDown();assertEquals(1,action.get(3,TimeUnit.SECONDS).turn());disconnect.get(3,TimeUnit.SECONDS);assertEquals("1",engine.snapshot(id,a).view().get("count"));}finally{rule.release.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(2,TimeUnit.SECONDS));}}
    private void awaitDeadline()throws Exception{var scheduler=Executors.newSingleThreadScheduledExecutor();var done=new CompletableFuture<Void>();try{scheduler.scheduleAtFixedRate(()->{if(System.currentTimeMillis()>engine.snapshot(id,a).deadlineEpochMillis()+10)done.complete(null);},0,5,TimeUnit.MILLISECONDS);done.get(2,TimeUnit.SECONDS);}finally{scheduler.shutdownNow();assertTrue(scheduler.awaitTermination(2,TimeUnit.SECONDS));}}
    private <T> List<T> race(Callable<T> first,Callable<T> second)throws Exception{var executor=Executors.newVirtualThreadPerTaskExecutor();var barrier=new CyclicBarrier(3);try{var a=executor.submit(()->{barrier.await(2,TimeUnit.SECONDS);return first.call();});var b=executor.submit(()->{barrier.await(2,TimeUnit.SECONDS);return second.call();});barrier.await(2,TimeUnit.SECONDS);return List.of(a.get(3,TimeUnit.SECONDS),b.get(3,TimeUnit.SECONDS));}finally{executor.shutdownNow();assertTrue(executor.awaitTermination(2,TimeUnit.SECONDS));}}
}
