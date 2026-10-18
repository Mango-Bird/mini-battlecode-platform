package dev.battlecode.botmatch;
import dev.battlecode.botrunner.BotSubmission;
import org.junit.jupiter.api.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
@Timeout(10)
class LocalSubmissionStoreTest {
    final LocalSubmissionStore store=new LocalSubmissionStore();
    @Test void freezesExactUtf8AndVersions() {
        String source="// Việt\r\nfunction chooseMove(o) { return o.legalMoves[0]; }";
        var first=store.create(UUID.randomUUID(),"Bot",source);UUID id=first.metadata().botId();
        assertEquals(BotSubmission.hash(source.getBytes(StandardCharsets.UTF_8)),first.metadata().sourceSha256());
        var second=store.save(UUID.randomUUID(),id,1,"Renamed",source+"\n");
        assertEquals(2,second.metadata().latestVersion());assertEquals(source,second.versions().getFirst().source());
        assertEquals(1,first.versions().size());assertNotEquals(first.metadata().sourceSha256(),second.metadata().sourceSha256());
        assertThrows(UnsupportedOperationException.class,()->second.versions().clear());
        assertFalse(second.toString().contains(source));
    }
    @Test void requestReplayDoesNotCreateVersionOrResurrectDeletedBot() {
        UUID request=UUID.randomUUID();var first=store.create(request,"Bot","source");
        assertEquals(first,store.create(request,"Bot","source"));assertEquals(1,store.list().size());
        assertThrows(IllegalStateException.class,()->store.create(request,"Other","source"));
        UUID save=UUID.randomUUID();var second=store.save(save,first.metadata().botId(),1,"Bot","new");
        assertEquals(second,store.save(save,first.metadata().botId(),1,"Bot","new"));
        UUID delete=UUID.randomUUID();store.delete(delete,first.metadata().botId(),2);
        assertDoesNotThrow(()->store.delete(delete,first.metadata().botId(),2));store.create(request,"Bot","source");
        assertTrue(store.list().isEmpty());
    }
    @Test void rejectsInvalidSourceAndBounds() {
        assertThrows(IllegalArgumentException.class,()->store.create(UUID.randomUUID(),"Bot",""));
        assertThrows(IllegalArgumentException.class,()->store.create(UUID.randomUUID(),"Bot","x".repeat(32769)));
        assertThrows(IllegalArgumentException.class,()->store.create(UUID.randomUUID(),"Bot","\uD800"));
        assertThrows(IllegalArgumentException.class,()->store.create(UUID.randomUUID()," Bad ","x"));
        assertThrows(IllegalArgumentException.class,()->store.create(UUID.randomUUID(),"\uD800","x"));
        var bot=store.create(UUID.randomUUID(),"Bot","x".repeat(32768));
        for(int i=1;i<16;i++)store.save(UUID.randomUUID(),bot.metadata().botId(),i,"Bot","x");
        assertThrows(IllegalStateException.class,()->store.save(UUID.randomUUID(),bot.metadata().botId(),16,"Bot","x"));
        for(int i=1;i<32;i++)store.create(UUID.randomUUID(),"Bot "+i,"x");
        assertThrows(IllegalStateException.class,()->store.create(UUID.randomUUID(),"Overflow","x"));
    }
    @Test void concurrentSavesHaveExactlyOneWinner() throws Exception {
        var bot=store.create(UUID.randomUUID(),"Bot","original");
        var barrier=new CyclicBarrier(2);
        try(var workers=Executors.newFixedThreadPool(2)) {
            Callable<Boolean> save=()-> {barrier.await(2,TimeUnit.SECONDS);try {store.save(UUID.randomUUID(),bot.metadata().botId(),1,"Bot","updated");return true;}catch(IllegalStateException e){return false;}};
            var a=workers.submit(save);var b=workers.submit(save);
            assertNotEquals(a.get(3,TimeUnit.SECONDS),b.get(3,TimeUnit.SECONDS));
        }
        assertEquals(2,store.get(bot.metadata().botId()).versions().size());
    }
    @Test void validateNeverClaimsSyntaxSafetyOrExecution() {
        var bot=store.create(UUID.randomUUID(),"Broken","this is not JavaScript");
        var result=store.validate(UUID.randomUUID(),bot.metadata().botId(),1);
        assertEquals("SOURCE_ACCEPTED",result.validationStatus());assertEquals("NOT_PERFORMED",result.staticValidation());
        assertEquals("NOT_VERIFIED",result.runtimeVerification());assertEquals("NOT_EXECUTABLE",result.executionEligibility());
    }
}
