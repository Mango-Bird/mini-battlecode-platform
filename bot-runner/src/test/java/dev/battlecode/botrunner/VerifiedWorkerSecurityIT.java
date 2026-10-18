package dev.battlecode.botrunner;

import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static dev.battlecode.botrunner.BotExecutionFailure.Code.*;

/** Explicit real-sandbox suite: missing Docker/image is a failure, never a skip. */
@Timeout(90)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VerifiedWorkerSecurityIT {
    static VerifiedWorkerRunner runner;
    static VerifiedWorkerRunner.Config config() throws Exception {
        return new VerifiedWorkerRunner.Config(Path.of(System.getProperty("battlecode.worker.python")),
            Path.of("../isolated-worker/worker.py"),Path.of(System.getProperty("battlecode.worker.docker")),
            Files.readString(Path.of("../.tools/phase8f/image-id.txt")).trim());
    }
    @BeforeAll static void start() throws Exception {runner=new VerifiedWorkerRunner(config());assertTrue(runner.capabilities().executable(),runner.capabilities().toString());}
    @AfterAll static void close(){if(runner!=null)runner.close();}
    static BotExecutionRequest request(String source) {
        byte[] bytes=source.getBytes(StandardCharsets.UTF_8);
        return new BotExecutionRequest(UUID.randomUUID(),new BotSubmission(UUID.randomUUID(),1,bytes,BotSubmission.hash(bytes)),
            RunnerContractTest.observation(),BotExecutionLimits.LOCAL);
    }
    static BotExecutionResult execute(String source) throws Exception {return runner.execute(request(source)).toCompletableFuture().get(10,TimeUnit.SECONDS);}
    @Test @Order(1) void liveKernelGateAndGuestTestsAllPass() {
        assertEquals(19,runner.capabilities().controls().size());
        assertTrue(runner.capabilities().controls().values().stream().allMatch("ENFORCED_AND_VERIFIED"::equals));
        assertTrue(runner.capabilities().tests().keySet().containsAll(Set.of("os_controls","process_memory_exhaustion","cancellation","cleanup")));
        assertTrue(runner.capabilities().tests().values().stream().allMatch("PASS"::equals));
    }
    @Test @Order(2) void validSourceAndFreshContexts() throws Exception {
        String source="globalThis.n=(globalThis.n||0)+1;function chooseMove(o){if(n!==1)throw Error();return o.legalMoves[0];}";
        assertNotNull(execute(source).action());assertNotNull(execute(source).action());
    }
    @Test @Order(3) void provenBotFailuresAndRecovery() throws Exception {
        for(var entry:Map.of("function chooseMove(o){return {};}",INVALID_ACTION,
            "function chooseMove(o){return {pieceId:' ',to:{row:1,col:0}};}",INVALID_ACTION,
            "function chooseMove( {",BOT_RUNTIME_ERROR,"while(true){}",BOT_TIMEOUT,
            "function chooseMove(o){return new Uint8Array(67108864);}",BOT_RESOURCE_LIMIT).entrySet()) {
            assertEquals(entry.getValue(),execute(entry.getKey()).failure().code());
        }
        assertNotNull(execute("function chooseMove(o){return o.legalMoves[0];}").action());
    }
    @Test @Order(4) void concurrentAdmissionIsBoundedAndCancellationCompletes() throws Exception {
        var first=request("while(true){}");var one=runner.execute(first).toCompletableFuture();
        var two=runner.execute(request("function chooseMove(o){return o.legalMoves[0];}")).toCompletableFuture();
        assertEquals(RUNNER_UNAVAILABLE,two.get(10,TimeUnit.SECONDS).failure().code());
        runner.cancel(first.executionId()).toCompletableFuture().get(10,TimeUnit.SECONDS);
        assertEquals(MATCH_CANCELLED,one.get(10,TimeUnit.SECONDS).failure().code());
        assertNotNull(execute("function chooseMove(o){return o.legalMoves[0];}").action());
    }
    @Test @Order(5) void matchGenerationsBoundCacheAndRejectOldScopes() throws Exception {
        String source="function chooseMove(o){return o.legalMoves[0];}";
        var old=runner.matchScope();assertNotNull(old.execute(request(source)).toCompletableFuture().get(10,TimeUnit.SECONDS).action());
        var next=runner.matchScope();assertNotNull(next.execute(request(source)).toCompletableFuture().get(10,TimeUnit.SECONDS).action());
        assertEquals(INTERNAL_ERROR,old.execute(request(source)).toCompletableFuture().get(10,TimeUnit.SECONDS).failure().code());
        assertNotNull(next.execute(request(source)).toCompletableFuture().get(10,TimeUnit.SECONDS).action());
    }
    @Test @Order(6) void workerCrashIsInfrastructureAndDisablesEligibility() throws Exception {
        var future=runner.execute(request("while(true){}")).toCompletableFuture();
        var worker=runner.supervisorProcess();
        worker.destroyForcibly();
        assertEquals(RUNNER_CRASH,future.get(15,TimeUnit.SECONDS).failure().code());
        assertFalse(runner.capabilities().executable());
        assertEquals(RUNNER_UNAVAILABLE,execute("function chooseMove(o){return o.legalMoves[0];}").failure().code());
    }
}
