package dev.battlecode.botrunner;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static dev.battlecode.botrunner.BotExecutionFailure.Code.*;
@Timeout(10)
class QuickJsPrototypeTest {
    static Path executable() {
        String configured=System.getProperty("battlecode.quickjs",System.getenv("BATTLECODE_QUICKJS"));
        Path path=configured==null?Path.of("../.tools/quickjs/qjs.exe").toAbsolutePath().normalize():Path.of(configured);
        // Never skip real-JS coverage: missing runtime is a visible setup failure.
        assertTrue(Files.isRegularFile(path),"Install pinned QuickJS or set BATTLECODE_QUICKJS / -Dbattlecode.quickjs");return path;
    }
    static BotExecutionRequest request(TrustedBotFixtures.Fixture fixture) {
        return new BotExecutionRequest(UUID.randomUUID(),TrustedBotFixtures.load(fixture),RunnerContractTest.observation(),BotExecutionLimits.LOCAL);
    }
    @Test void actualJavaScriptReturnsMoveAndCleansProcess() throws Exception {
        try(var runner=new TrustedQuickJsRunner(executable())) {
            assertEquals("A-R1",runner.execute(request(TrustedBotFixtures.Fixture.FirstLegalMoveBot)).toCompletableFuture().get(4,TimeUnit.SECONDS).action().pieceId());
            assertEquals(0,runner.liveProcesses());assertFalse(runner.capabilities().get("osIsolationVerified"));
        }
    }
    @Test void bothProductionSampleFixturesExecute() throws Exception {
        try(var runner=new TrustedQuickJsRunner(executable())) {
            assertNotNull(runner.execute(request(TrustedBotFixtures.Fixture.CapturePriorityBot)).toCompletableFuture().get(4,TimeUnit.SECONDS).action());
        }
    }
    @Test void capturePrioritySelectsOccupiedEnemyDestination() throws Exception {
        var observation=new dev.battlecode.rulesapi.bot.BotObservation("bot-1","ott-board-9x9-v1","A",1,9,
            List.of(new dev.battlecode.rulesapi.bot.BotPiece("B-S1","B","SCISSORS",0,2)),
            List.of(new dev.battlecode.rulesapi.bot.BotMove("A-R1",new dev.battlecode.rulesapi.bot.BotPosition(0,1)),
                new dev.battlecode.rulesapi.bot.BotMove("A-R1",new dev.battlecode.rulesapi.bot.BotPosition(0,2))),1000);
        try(var runner=new TrustedQuickJsRunner(executable())) {
            var request=new BotExecutionRequest(UUID.randomUUID(),TrustedBotFixtures.load(TrustedBotFixtures.Fixture.CapturePriorityBot),observation,BotExecutionLimits.LOCAL);
            var result=runner.execute(request).toCompletableFuture().get(4,TimeUnit.SECONDS);
            assertEquals(2,result.action().to().col());
        }
    }
    @Test void invalidActionIsBotFailure() throws Exception {fault(TrustedBotFixtures.Fixture.InvalidActionBot,INVALID_ACTION);}
    @Test void missingFunctionIsRuntimeFailure() throws Exception {fault(TrustedBotFixtures.Fixture.MissingFunctionBot,BOT_RUNTIME_ERROR);}
    @Test void runtimeExceptionIsBotFailure() throws Exception {fault(TrustedBotFixtures.Fixture.RuntimeErrorBot,BOT_RUNTIME_ERROR);}
    @Test void promiseIsRejected() throws Exception {fault(TrustedBotFixtures.Fixture.PromiseBot,BOT_RUNTIME_ERROR);}
    @Test void infiniteLoopWallWatchdogKillsChild() throws Exception {fault(TrustedBotFixtures.Fixture.InfiniteLoopBot,BOT_TIMEOUT);}
    void fault(TrustedBotFixtures.Fixture fixture,BotExecutionFailure.Code expected) throws Exception {
        try(var runner=new TrustedQuickJsRunner(executable())) {
            var result=runner.execute(request(fixture)).toCompletableFuture().get(4,TimeUnit.SECONDS);
            assertNotNull(result.failure(),()->result.toString());assertEquals(expected,result.failure().code());assertEquals(0,runner.liveProcesses());
        }
    }
    @Test void freshContextForEveryTurn() throws Exception {
        try(var runner=new TrustedQuickJsRunner(executable())) {
            for(int i=0;i<3;i++)assertNotNull(runner.execute(request(TrustedBotFixtures.Fixture.FreshContextBot)).toCompletableFuture().get(4,TimeUnit.SECONDS).action());
        }
    }
    @Test void arbitrarySourceFailsClosed() throws Exception {
        try(var runner=new TrustedQuickJsRunner(executable())) {
            var request=new BotExecutionRequest(UUID.randomUUID(),BotSubmission.of(UUID.randomUUID(),1,"function chooseMove(o){return o.legalMoves[0]}"),RunnerContractTest.observation(),BotExecutionLimits.LOCAL);
            assertEquals(RUNNER_UNAVAILABLE,runner.execute(request).toCompletableFuture().get(1,TimeUnit.SECONDS).failure().code());assertEquals(0,runner.liveProcesses());
        }
    }
    @Test void cancellationDuringStartupAndShutdownCleanUp() throws Exception {
        var runner=new TrustedQuickJsRunner(executable());var request=request(TrustedBotFixtures.Fixture.InfiniteLoopBot);
        var result=runner.execute(request);runner.cancel(request.executionId()).toCompletableFuture().get(4,TimeUnit.SECONDS);
        assertEquals(MATCH_CANCELLED,result.toCompletableFuture().get(1,TimeUnit.SECONDS).failure().code());runner.close();assertEquals(0,runner.liveProcesses());
    }
    @Test void unavailableRuntimeClassifiedAsInfrastructure() throws Exception {
        try(var runner=new TrustedQuickJsRunner(Path.of("nonexistent-quickjs.exe"))) {
            assertEquals(RUNNER_UNAVAILABLE,runner.execute(request(TrustedBotFixtures.Fixture.FirstLegalMoveBot)).toCompletableFuture().get(1,TimeUnit.SECONDS).failure().code());
        }
    }
}
