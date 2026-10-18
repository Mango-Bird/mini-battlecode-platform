package dev.battlecode.botmatch;
import dev.battlecode.botrunner.*;
import dev.battlecode.competition.*;
import dev.battlecode.competition.MatchException;
import dev.battlecode.engine.*;
import dev.battlecode.rulesapi.*;
import dev.battlecode.rulesapi.bot.*;
import dev.battlecode.rulesimpl.*;
import dev.battlecode.rulesimpl.board.*;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static dev.battlecode.botrunner.BotExecutionFailure.Code.*;
@Timeout(40)
class BotMatchCoordinatorTest {
    final OttBoardRule rule=new OttBoardRule();
    final Map<String,GameRule> rules=Map.of(rule.id(),rule,RockPaperScissorsRule.ID,new RockPaperScissorsRule(),NumberGuessingRule.ID,new NumberGuessingRule());
    final RuleRegistry registry=new RuleRegistry(){public Optional<GameRule> find(String id){return Optional.ofNullable(rules.get(id));}public Set<String> ruleIds(){return rules.keySet();}};
    final InMemoryLobby lobby=new InMemoryLobby(registry);
    final DefaultGameEngine engine=new DefaultGameEngine();
    final DefaultMatchManager manager=new DefaultMatchManager(lobby,engine,registry,n->{});
    BotRunner runner;
    BotMatchCoordinator coordinator;
    void start(BotRunner delegate,String mode) {runner=new BotExecutionService(delegate);coordinator=new BotMatchCoordinator(lobby,manager,rule,runner,mode);}
    TrustedQuickJsRunner actual() {
        String configured=System.getProperty("battlecode.quickjs",System.getenv("BATTLECODE_QUICKJS"));
        Path path=configured==null?Path.of("../.tools/quickjs/qjs.exe").toAbsolutePath().normalize():Path.of(configured);
        assertTrue(Files.isRegularFile(path),"Pinned QuickJS required; tests must not skip");return new TrustedQuickJsRunner(path);
    }
    BotMatchCoordinator.Summary play(TrustedBotFixtures.Fixture first,TrustedBotFixtures.Fixture second) throws Exception {
        return coordinator.start(TrustedBotFixtures.load(first),TrustedBotFixtures.load(second)).completion().toCompletableFuture().get(30,TimeUnit.SECONDS);
    }
    @AfterEach void cleanup() throws Exception {
        if(coordinator!=null)coordinator.close();if(runner!=null)runner.close();manager.shutdown();assertTrue(manager.awaitTermination(2,TimeUnit.SECONDS));assertEquals(0,manager.activeMatches());
    }
    @Test void actualJavaScriptBotVsBotFinishesAndValidatedReplayMatchesFinalBoard() throws Exception {
        var actual=actual();start(actual,TrustedQuickJsRunner.MODE);
        var summary=play(TrustedBotFixtures.Fixture.FirstLegalMoveBot,TrustedBotFixtures.Fixture.CapturePriorityBot);
        assertNotNull(summary.result());assertFalse(summary.history().isEmpty());assertTrue(summary.history().size()<=200);
        assertTrue(Set.of("GOAL_REACHED","PIECE_TYPE_ELIMINATED","NO_LEGAL_MOVES","MOVE_LIMIT_REACHED").contains(summary.result().reason()),summary.result().reason());
        assertNotEquals("BOT_COORDINATOR_INFRASTRUCTURE",summary.result().reason());
        assertNotEquals("RECORD_CAPACITY",summary.result().reason());
        var replay=Board.replay(summary.history());
        var pieces=replay.pieces().stream().map(p->new BotPiece(p.id(),p.owner().name(),p.type().name(),p.position().row(),p.position().col()))
            .sorted(Comparator.comparing(BotPiece::id)).toList();
        assertEquals(pieces,summary.finalObservation().board());
        assertThrows(UnsupportedOperationException.class,()->summary.history().clear());assertEquals(0,actual.liveProcesses());
        assertEquals(TrustedQuickJsRunner.MODE,summary.runtimeMode());
        assertEquals(1,summary.versionA());assertEquals(1,summary.versionB());
        assertTrue(summary.runtimeFingerprint().endsWith(TrustedQuickJsRunner.ARTIFACT_SHA256));assertEquals(BotExecutionLimits.LOCAL,summary.limits());
        assertEquals(0,manager.activeMatches());
    }
    @Test void invalidActualBotForfeitsWithoutValidatedMove() throws Exception {
        start(actual(),TrustedQuickJsRunner.MODE);var summary=play(TrustedBotFixtures.Fixture.InvalidActionBot,TrustedBotFixtures.Fixture.FirstLegalMoveBot);
        assertEquals("FORFEIT",summary.result().reason());assertEquals(1,summary.result().winners().size());assertTrue(summary.history().isEmpty());
    }
    @Test void provenActualGuestTimeoutForfeits() throws Exception {
        start(actual(),TrustedQuickJsRunner.MODE);var summary=play(TrustedBotFixtures.Fixture.InfiniteLoopBot,TrustedBotFixtures.Fixture.FirstLegalMoveBot);
        assertEquals("FORFEIT",summary.result().reason());assertEquals(1,summary.result().winners().size());
    }
    @Test void unavailableInfrastructureCancelsWithoutLoser() throws Exception {
        start(new FakeBotRunner(r->CompletableFuture.completedFuture(BotExecutionResult.failed(r.executionId(),RUNNER_UNAVAILABLE,"x"))),"FAKE_TEST_ONLY");
        var summary=play(TrustedBotFixtures.Fixture.FirstLegalMoveBot,TrustedBotFixtures.Fixture.CapturePriorityBot);
        assertEquals("RUNNER_UNAVAILABLE",summary.result().reason());assertTrue(summary.result().winners().isEmpty());
    }
    @Test void unknownCrashCancelsWithoutLoser() throws Exception {
        start(new FakeBotRunner(r->CompletableFuture.completedFuture(BotExecutionResult.failed(r.executionId(),RUNNER_CRASH,"UNKNOWN_PROCESS_EXIT"))),"FAKE_TEST_ONLY");
        var summary=play(TrustedBotFixtures.Fixture.FirstLegalMoveBot,TrustedBotFixtures.Fixture.CapturePriorityBot);
        assertEquals("RUNNER_CRASH",summary.result().reason());assertTrue(summary.result().winners().isEmpty());
    }
    @Test void wrongExecutionCorrelationCancels() throws Exception {
        start(new FakeBotRunner(r->CompletableFuture.completedFuture(BotExecutionResult.failed(UUID.randomUUID(),BOT_TIMEOUT,"x"))),"FAKE_TEST_ONLY");
        assertEquals("INTERNAL_ERROR",play(TrustedBotFixtures.Fixture.FirstLegalMoveBot,TrustedBotFixtures.Fixture.CapturePriorityBot).result().reason());
    }
    record Started(UUID id,UUID a,UUID b) { }
    Started internalMatch() {
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();lobby.register(a,"A");lobby.register(b,"B");
        var room=lobby.createRoom(a,rule.id(),new GameConfig(2,200,10000,Map.of()));lobby.joinRoom(b,room.roomId());
        return new Started(manager.startBotMatch(a,room.roomId()),a,b);
    }
    BotMatchAccess.Lease lease(Started s) {return manager.captureBotTurn(s.id(),(state,player)->rule.observation(state,player,1000)).orElseThrow();}
    Map<String,String> action(BotMatchAccess.Lease lease) {var move=lease.observation().legalMoves().getFirst();return Map.of("pieceId",move.pieceId(),"to.row",""+move.to().row(),"to.col",""+move.to().col());}
    @Test void serverSeatsRejectManualAndOldLeaseCannotCommit() {
        var started=internalMatch();var lease=lease(started);
        assertThrows(MatchException.class,()->manager.submitAction(started.a(),started.id(),0,action(lease)));
        assertThrows(MatchException.class,()->lease(started));
        assertTrue(manager.commitBotTurn(lease,action(lease)).isPresent());assertTrue(manager.commitBotTurn(lease,action(lease)).isEmpty());
        assertEquals(1,lease(started).expectedTurn());manager.cancelBotMatch(started.id(),"TEST_CANCEL");
    }
    @Test void concurrentLeaseCommitExactlyOnce() throws Exception {
        var started=internalMatch();var lease=lease(started);var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Boolean> task=()->{assertTrue(gate.await(2,TimeUnit.SECONDS));return manager.commitBotTurn(lease,action(lease)).isPresent();};
            var one=pool.submit(task);var two=pool.submit(task);gate.countDown();assertNotEquals(one.get(2,TimeUnit.SECONDS),two.get(2,TimeUnit.SECONDS));
        }
        assertEquals(1,engine.snapshot(started.id(),started.b()).turn());manager.cancelBotMatch(started.id(),"TEST_CANCEL");
    }
    @Test void leaseAfterCancellationCannotOverwriteTerminal() {
        var started=internalMatch();var lease=lease(started);manager.cancelBotMatch(started.id(),"TEST_CANCEL");
        assertTrue(manager.commitBotTurn(lease,action(lease)).isEmpty());assertTrue(manager.forfeitBotTurn(lease).isEmpty());
        assertEquals("TEST_CANCEL",manager.result(started.id()).orElseThrow().reason());
    }
    @Test void botSeatDisconnectCancelsAndDoesNotInvokeRuleForfeit() {
        var started=internalMatch();manager.disconnect(started.a());assertEquals("BOT_SEAT_DISCONNECTED",manager.result(started.id()).orElseThrow().reason());
        assertTrue(manager.result(started.id()).orElseThrow().winners().isEmpty());
    }
    @Test void managerShutdownRejectsLateLease() {
        var started=internalMatch();var lease=lease(started);manager.shutdown();assertTrue(manager.commitBotTurn(lease,action(lease)).isEmpty());
        assertEquals("SERVER_SHUTDOWN",manager.result(started.id()).orElseThrow().reason());
    }
    @Test void botWatchdogCancelsUnexplainedExpiredTurnInsteadOfForfeit() throws Exception {
        var started=internalMatch();var lease=lease(started);
        long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
        while(manager.result(started.id()).isEmpty() && System.nanoTime()<limit)Thread.sleep(20);
        assertEquals("BOT_INFRASTRUCTURE_DEADLINE",manager.result(started.id()).orElseThrow().reason());
        assertEquals(MatchStatus.CANCELLED,engine.snapshot(started.id(),started.a()).status());
        assertTrue(manager.result(started.id()).orElseThrow().winners().isEmpty());assertTrue(manager.forfeitBotTurn(lease).isEmpty());
    }
    @Test void cancelWhileRunnerPendingDiscardsLateResponse() throws Exception {
        var entered=new CountDownLatch(1);var pending=new CompletableFuture<BotExecutionResult>();
        start(new FakeBotRunner(r->{entered.countDown();return pending;}),"FAKE_TEST_ONLY");
        var handle=coordinator.start(TrustedBotFixtures.load(TrustedBotFixtures.Fixture.FirstLegalMoveBot),TrustedBotFixtures.load(TrustedBotFixtures.Fixture.CapturePriorityBot));
        assertTrue(entered.await(2,TimeUnit.SECONDS));coordinator.cancel(handle.matchId());
        var summary=handle.completion().toCompletableFuture().get(3,TimeUnit.SECONDS);assertTrue(summary.history().isEmpty());
        assertEquals("MATCH_CANCELLED",summary.result().reason());assertTrue(summary.result().winners().isEmpty());
    }
}
