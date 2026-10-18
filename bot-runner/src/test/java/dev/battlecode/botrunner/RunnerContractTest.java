package dev.battlecode.botrunner;
import dev.battlecode.rulesapi.bot.*;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static dev.battlecode.botrunner.BotExecutionFailure.Code.*;
@Timeout(10)
class RunnerContractTest {
    static BotObservation observation() {
        return new BotObservation("bot-1","ott-board-9x9-v1","A",1,9,List.of(),List.of(new BotMove("A-R1",new BotPosition(0,1))),1000);
    }
    static BotExecutionRequest request(UUID id) {return new BotExecutionRequest(id,TrustedBotFixtures.load(TrustedBotFixtures.Fixture.FirstLegalMoveBot),observation(),BotExecutionLimits.LOCAL);}
    static BotExecutionResult ok(BotExecutionRequest r) {return new BotExecutionResult(r.executionId(),new BotAction("A-R1",new BotPosition(0,1)),null,1);}
    @Test void sourceHashAndDefensiveCopy() {
        byte[] bytes="function chooseMove(o) {}".getBytes(StandardCharsets.UTF_8);
        var submission=new BotSubmission(UUID.randomUUID(),1,bytes,BotSubmission.hash(bytes));
        bytes[0]=0;submission.source()[0]=0;assertTrue(submission.sourceText().startsWith("function"));
        assertFalse(submission.toString().contains("chooseMove"));
    }
    @Test void rejectsHashMismatch() {assertThrows(IllegalArgumentException.class,()->new BotSubmission(UUID.randomUUID(),1,new byte[]{1},"00"));}
    @Test void rejectsBadUtf8() {assertThrows(IllegalArgumentException.class,()->new BotSubmission(UUID.randomUUID(),1,new byte[]{(byte)0xff},"00"));}
    @Test void rejectsLargeSource() {assertThrows(IllegalArgumentException.class,()->new BotSubmission(UUID.randomUUID(),1,new byte[32769],"00"));}
    @Test void exactResultUnion() {
        assertThrows(IllegalArgumentException.class,()->new BotExecutionResult(UUID.randomUUID(),null,null,0));
        assertThrows(IllegalArgumentException.class,()->new BotExecutionResult(UUID.randomUUID(),new BotAction("x",new BotPosition(0,0)),new BotExecutionFailure(INTERNAL_ERROR,"x"),0));
    }
    @Test void limitsAndBudgetValidated() {
        assertThrows(IllegalArgumentException.class,()->new BotExecutionLimits(1001,250,128L<<20,32L<<20,1L<<20,1024));
        var obs=new BotObservation("bot-1","ott-board-9x9-v1","A",1,9,List.of(),List.of(),1);
        assertThrows(IllegalArgumentException.class,()->new BotExecutionRequest(UUID.randomUUID(),request(UUID.randomUUID()).submission(),obs,BotExecutionLimits.LOCAL));
    }
    @Test void strictActionValid() throws Exception {assertEquals("A-R1",StrictActionCodec.parse("{\"pieceId\":\"A-R1\",\"to\":{\"row\":0,\"col\":1}}".getBytes(StandardCharsets.UTF_8),1024).pieceId());}
    @Test void strictActionRejectsExtraDuplicateFloatTrailingAndOversize() {
        for(String json:List.of("{\"pieceId\":\"x\",\"pieceId\":\"y\",\"to\":{\"row\":0,\"col\":1}}",
            "{\"pieceId\":\"x\",\"to\":{\"row\":0.0,\"col\":1}}","{\"pieceId\":\"x\",\"to\":{\"row\":0,\"col\":1},\"extra\":true}","{} {}","null"))
            assertThrows(java.io.IOException.class,()->StrictActionCodec.parse(json.getBytes(StandardCharsets.UTF_8),1024));
        assertThrows(java.io.IOException.class,()->StrictActionCodec.parse(new byte[1025],1024));
    }
    @Test void duplicateRunsOnceAndConflictRejected() throws Exception {
        AtomicInteger count=new AtomicInteger();
        try(var service=new BotExecutionService(new FakeBotRunner(r->{count.incrementAndGet();return CompletableFuture.completedFuture(ok(r));}))) {
            var request=request(UUID.randomUUID());var first=service.execute(request).toCompletableFuture().get(2,TimeUnit.SECONDS);
            assertEquals(first,service.execute(request).toCompletableFuture().get(2,TimeUnit.SECONDS));assertEquals(1,count.get());
            var conflict=new BotExecutionRequest(request.executionId(),TrustedBotFixtures.load(TrustedBotFixtures.Fixture.CapturePriorityBot),observation(),BotExecutionLimits.LOCAL);
            assertEquals(INTERNAL_ERROR,service.execute(conflict).toCompletableFuture().get(2,TimeUnit.SECONDS).failure().code());
        }
    }
    @Test void boundedQueueAndCancellation() throws Exception {
        var entered=new CountDownLatch(1);var pending=new CompletableFuture<BotExecutionResult>();
        try(var service=new BotExecutionService(new FakeBotRunner(r->{entered.countDown();return pending;}))) {
            var first=request(UUID.randomUUID());var result=service.execute(first);assertTrue(entered.await(2,TimeUnit.SECONDS));
            var queued=new ArrayList<BotExecutionRequest>();
            for(int i=0;i<4;i++){var r=request(UUID.randomUUID());queued.add(r);service.execute(r);}
            assertEquals(4,service.pendingCount());
            assertEquals(RUNNER_UNAVAILABLE,service.execute(request(UUID.randomUUID())).toCompletableFuture().get(2,TimeUnit.SECONDS).failure().code());
            service.cancel(first.executionId()).toCompletableFuture().get(2,TimeUnit.SECONDS);
            assertEquals(MATCH_CANCELLED,result.toCompletableFuture().get(2,TimeUnit.SECONDS).failure().code());
            queued.forEach(r->service.cancel(r.executionId()));
        }
    }
    @Test void wrongCorrelationIsInfrastructure() throws Exception {
        try(var service=new BotExecutionService(new FakeBotRunner(r->CompletableFuture.completedFuture(BotExecutionResult.failed(UUID.randomUUID(),BOT_TIMEOUT,"x"))))) {
            assertEquals(INTERNAL_ERROR,service.execute(request(UUID.randomUUID())).toCompletableFuture().get(2,TimeUnit.SECONDS).failure().code());
        }
    }
    @Test void cancellationWaitsForDelegateRegistrationRace() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var pending=new CompletableFuture<BotExecutionResult>();
        try(var service=new BotExecutionService(new FakeBotRunner(r->{
            entered.countDown();try {if(!release.await(2,TimeUnit.SECONDS))throw new IllegalStateException("test gate");}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}return pending;
        }))) {
            var request=request(UUID.randomUUID());var result=service.execute(request);
            assertTrue(entered.await(2,TimeUnit.SECONDS));var cancelled=service.cancel(request.executionId()).toCompletableFuture();
            assertFalse(cancelled.isDone(),"cancel must await registration/cleanup, not just set a flag");
            release.countDown();cancelled.get(2,TimeUnit.SECONDS);
            assertEquals(MATCH_CANCELLED,result.toCompletableFuture().get(2,TimeUnit.SECONDS).failure().code());assertTrue(pending.isDone());
        } finally {release.countDown();}
    }
    @Test void boundedRecordsFailClosedAndNoEviction() throws Exception {
        try(var service=new BotExecutionService(new FakeBotRunner(r->CompletableFuture.completedFuture(ok(r))))) {
            var first=request(UUID.randomUUID());service.execute(first).toCompletableFuture().get(2,TimeUnit.SECONDS);
            for(int i=1;i<256;i++)service.execute(request(UUID.randomUUID())).toCompletableFuture().get(2,TimeUnit.SECONDS);
            assertEquals(256,service.recordCount());
            assertEquals(RUNNER_UNAVAILABLE,service.execute(request(UUID.randomUUID())).toCompletableFuture().get(2,TimeUnit.SECONDS).failure().code());
            assertNotNull(service.execute(first).toCompletableFuture().get(2,TimeUnit.SECONDS).action());
        }
    }
    @Test void closedServiceRejectsWithoutExecution() throws Exception {
        var service=new BotExecutionService(new FakeBotRunner(r->{fail("delegate should not run");return null;}));service.close();
        assertEquals(RUNNER_UNAVAILABLE,service.execute(request(UUID.randomUUID())).toCompletableFuture().get(2,TimeUnit.SECONDS).failure().code());
    }
}
