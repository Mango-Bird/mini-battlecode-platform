package dev.battlecode.botmatch;
import dev.battlecode.botmatch.ranked.*;
import dev.battlecode.botmatch.persistence.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
@Timeout(60)
class RankedPostgresIT {
    RatingPostgresIT fixture;MatchmakingService service;
    @BeforeEach void setup(){fixture=new RatingPostgresIT();fixture.setup();var auth=new AuthService(fixture.db);auth.login("alice","long-local-test-password");auth.login("bob","long-local-test-password");service=new MatchmakingService(fixture.db,new PostgresSubmissionRepository(fixture.db),()->true,MatchmakingPolicy.V1,Clock.fixed(RatingPostgresIT.TIME,ZoneOffset.UTC));}
    @AfterEach void close(){fixture.cleanup();}
    MatchmakingTicket join(boolean a,UUID request){var b=a?fixture.a:fixture.b;return service.join(b.ownerId(),request,b.botId(),b.version(),b.sourceSha256());}
    @Test void joinCancelFreezeAndAtomicReserve()throws Exception{
        UUID request=UUID.randomUUID();var a=join(true,request);assertEquals(a,join(true,request));assertThrows(IllegalStateException.class,()->join(true,UUID.randomUUID()));
        assertThrows(SecurityException.class,()->service.join(fixture.a.ownerId(),UUID.randomUUID(),fixture.b.botId(),1,fixture.b.sourceSha256()));
        join(false,UUID.randomUUID());var pool=Executors.newFixedThreadPool(2);
        RankedPairing pair;try{var futures=pool.invokeAll(List.<Callable<RankedPairing>>of(service::reserve,service::reserve));var values=new ArrayList<RankedPairing>();for(var f:futures)if(f.get()!=null)values.add(f.get());assertEquals(1,values.size());pair=values.getFirst();}finally{pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
        assertNotEquals(pair.a().ownerId(),pair.b().ownerId());assertNotEquals(pair.a().botId(),pair.b().botId());assertEquals("RESERVED",service.me(fixture.a.ownerId()).ticket().status());assertThrows(IllegalStateException.class,()->service.cancel(fixture.a.ownerId(),UUID.randomUUID()));
        service.recover();assertEquals("INTERRUPTED",service.me(fixture.a.ownerId()).ticket().status());assertEquals(0,fixture.count("rating_events"));assertNull(service.reserve());
    }
    @Test void resultCommitSidesDuplicateAndRepeat(){join(true,UUID.randomUUID());join(false,UUID.randomUUID());var pair=service.reserve();var board=dev.battlecode.rulesimpl.board.Board.initial().pieces();
        for(int n=1;n<=2;n++){UUID battle=UUID.randomUUID();service.running(pair,n,battle);assertThrows(IllegalStateException.class,()->service.running(pair,1,battle));var result=new LocalArenaService.Result("FORFEIT","A","INVALID_ACTION");var snapshot=new LocalArenaService.Snapshot(battle,"A","B","FINISHED",1,"A",board,List.of(),List.of(board),result);service.complete(pair,n,snapshot);service.complete(pair,n,snapshot);}
        assertEquals(4,fixture.count("rating_events"));assertEquals(2,fixture.count("match_scoring_ledger"));assertEquals("COMPLETED",service.me(fixture.a.ownerId()).ticket().status());
        assertEquals(2,service.matches(fixture.a.ownerId()).size());assertThrows(NoSuchElementException.class,()->service.match(UUID.randomUUID(),MatchmakingService.gameId(pair.pairingId(),1)));
        join(true,UUID.randomUUID());join(false,UUID.randomUUID());assertNull(service.reserve());service.recover();assertEquals(4,fixture.count("rating_events"));
    }
    @Test void cancellationGateAndRateLimit(){var a=join(true,UUID.randomUUID());UUID request=UUID.randomUUID();var cancelled=service.cancel(fixture.a.ownerId(),request);assertEquals("CANCELLED",cancelled.status());assertEquals(cancelled,service.cancel(fixture.a.ownerId(),request));
        var refused=new MatchmakingService(fixture.db,new PostgresSubmissionRepository(fixture.db),()->false);assertThrows(IllegalStateException.class,()->refused.join(fixture.a.ownerId(),UUID.randomUUID(),fixture.a.botId(),1,fixture.a.sourceSha256()));
        for(int n=0;n<9;n++){join(true,UUID.randomUUID());service.cancel(fixture.a.ownerId(),UUID.randomUUID());}assertThrows(IllegalStateException.class,()->join(true,UUID.randomUUID()));
    }
    @Test void waitingExpiryAndConstraintRollback(){var a=join(true,UUID.randomUUID());assertThrows(Database.Unavailable.class,()->fixture.db.transaction(c->{try(var p=c.prepareStatement("UPDATE ranked_queue_tickets SET bot_version=2 WHERE ticket_id=?")){p.setObject(1,a.ticketId());p.executeUpdate();}return null;}));
        var later=new MatchmakingService(fixture.db,new PostgresSubmissionRepository(fixture.db),()->true,MatchmakingPolicy.V1,Clock.fixed(RatingPostgresIT.TIME.plusSeconds(601),ZoneOffset.UTC));later.recover();assertEquals("EXPIRED",later.me(fixture.a.ownerId()).ticket().status());}
    @Test void windowsAndConcurrentJoin()throws Exception{
        fixture.service.confirm(fixture.result(UUID.randomUUID(),"DRAW",true));
        fixture.db.transaction(c->{try(var p=c.prepareStatement("UPDATE rating_profiles SET elo=1710 WHERE bot_id=?")){p.setObject(1,fixture.b.botId());p.executeUpdate();}return null;});
        var pool=Executors.newFixedThreadPool(2);try{var results=pool.invokeAll(List.<Callable<Boolean>>of(()->{try{join(true,UUID.randomUUID());return true;}catch(IllegalStateException e){return false;}},()->{try{join(true,UUID.randomUUID());return true;}catch(IllegalStateException e){return false;}}));assertNotEquals(results.get(0).get(),results.get(1).get());}finally{pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
        join(false,UUID.randomUUID());assertNull(service.reserve());
        var later=new MatchmakingService(fixture.db,new PostgresSubmissionRepository(fixture.db),()->true,MatchmakingPolicy.V1,Clock.fixed(RatingPostgresIT.TIME.plusSeconds(60),ZoneOffset.UTC));assertNotNull(later.reserve());
    }
}
