package dev.battlecode.botmatch;
import dev.battlecode.botmatch.persistence.*;
import dev.battlecode.botmatch.ranked.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
@Timeout(60)
class CompetitiveReadPostgresIT {
    RankedPostgresIT fixture;CompetitiveReadService read;
    @BeforeEach void setup(){fixture=new RankedPostgresIT();fixture.setup();read=new CompetitiveReadService(fixture.fixture.db);}
    @AfterEach void close(){fixture.close();}
    @Test void participantPrivacySideIntegrityAndConfirmedRatings()throws Exception{
        var a=fixture.fixture.a;var b=fixture.fixture.b;
        assertThrows(NoSuchElementException.class,()->read.profile(b.ownerId(),a.botId()));assertEquals(true,read.profile(a.ownerId(),a.botId()).get("canEdit"));
        fixture.join(true,UUID.randomUUID());fixture.join(false,UUID.randomUUID());var pair=fixture.service.reserve();UUID id=MatchmakingService.gameId(pair.pairingId(),1);
        assertNull(read.match(a.ownerId(),id).snapshot());assertThrows(NoSuchElementException.class,()->read.match(UUID.randomUUID(),id));
        var board=dev.battlecode.rulesimpl.board.Board.initial().pieces();UUID battle=UUID.randomUUID();fixture.service.running(pair,1,battle);
        var snapshot=new LocalArenaService.Snapshot(battle,a.botId()+":v1:"+a.sourceSha256(),b.botId()+":v1:"+b.sourceSha256(),"FINISHED",1,"A",board,List.of(),List.of(board),new LocalArenaService.Result("FORFEIT","A","INVALID_ACTION"));
        fixture.service.complete(pair,1,snapshot);var match=read.match(a.ownerId(),id);assertEquals("COMPLETED",match.status());assertEquals("FINISHED",match.snapshot().status());assertEquals(2,match.ratingChanges().size());assertEquals(board,match.snapshot().board());
        String payload=PersistenceJson.mapper().writeValueAsString(match);assertFalse(payload.contains("chooseMove"));assertFalse(payload.contains(a.sourceSha256()));assertFalse(payload.contains("ownerId"));
        assertEquals(false,read.profile(b.ownerId(),a.botId()).get("canEdit"));assertEquals(0,read.history(UUID.randomUUID()).size());
        fixture.service.recover();assertEquals(match,read.match(b.ownerId(),id));assertEquals("INTERRUPTED",read.match(a.ownerId(),MatchmakingService.gameId(pair.pairingId(),2)).status());
    }
    @Test void rejectsDamagedReplayAndDoesNotExecute(){fixture.join(true,UUID.randomUUID());fixture.join(false,UUID.randomUUID());var pair=fixture.service.reserve();UUID id=MatchmakingService.gameId(pair.pairingId(),1),battle=UUID.randomUUID();fixture.service.running(pair,1,battle);
        var board=dev.battlecode.rulesimpl.board.Board.initial().pieces();var bad=new LocalArenaService.Snapshot(battle,"A","B","RUNNING",1,"A",board,List.of(),List.of(),null);fixture.service.progress(id,bad);
        assertThrows(IllegalStateException.class,()->read.match(fixture.fixture.a.ownerId(),id));assertEquals(0,fixture.fixture.count("rating_events"));}
    @Test void historyIsOwnerScopedAndSummariesAreBounded()throws Exception{
        UUID owner=fixture.fixture.a.ownerId();var board=dev.battlecode.rulesimpl.board.Board.initial().pieces();
        fixture.fixture.db.transaction(c->{for(int n=0;n<51;n++){UUID id=UUID.randomUUID();var snapshot=new LocalArenaService.Snapshot(id,"FirstLegalMoveBot","CapturePriorityBot","FINISHED",1,"A",board,List.of(),List.of(board),new LocalArenaService.Result("DRAW",null,"DRAW"));try(var p=c.prepareStatement("INSERT INTO arena_battles(request_id,owner_id,payload_hash,battle_id,status,snapshot) VALUES (?,?,?,?,'FINISHED',?::jsonb)")){p.setObject(1,UUID.randomUUID());p.setObject(2,owner);p.setString(3,"0".repeat(64));p.setObject(4,id);p.setString(5,PersistenceJson.mapper().writeValueAsString(snapshot));p.executeUpdate();}}return null;});
        var history=read.history(owner);assertEquals(50,history.size());assertTrue(history.stream().allMatch(m->m.snapshot().frames().isEmpty()));assertEquals(0,read.history(fixture.fixture.b.ownerId()).size());
        assertEquals(1,read.history(owner,1).size());assertThrows(IllegalArgumentException.class,()->read.history(owner,201));
    }
    @Test void concurrentCompletionCannotMixOldSnapshotWithNewRating()throws Exception{
        fixture.join(true,UUID.randomUUID());fixture.join(false,UUID.randomUUID());var pair=fixture.service.reserve();UUID id=MatchmakingService.gameId(pair.pairingId(),1),battle=UUID.randomUUID();fixture.service.running(pair,1,battle);
        var board=dev.battlecode.rulesimpl.board.Board.initial().pieces();fixture.service.progress(id,new LocalArenaService.Snapshot(battle,"A","B","RUNNING",1,"A",board,List.of(),List.of(board),null));
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try(var writer=fixture.fixture.db.open()){
            writer.setAutoCommit(false);try(var statement=writer.createStatement()){statement.execute("LOCK TABLE rating_events IN ACCESS EXCLUSIVE MODE");}
            var pending=pool.submit(()->read.match(pair.a().ownerId(),id));
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);boolean blocked=false;
            while(System.nanoTime()<deadline){blocked=fixture.fixture.db.transaction(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE '%FROM rating_events WHERE match_id%')")){r.next();return r.getBoolean(1);}});if(blocked)break;Thread.sleep(20);}
            assertTrue(blocked,"Reader must have captured the running snapshot before writer completion");
            var finished=new LocalArenaService.Snapshot(battle,"A","B","FINISHED",1,"A",board,List.of(),List.of(board),new LocalArenaService.Result("FORFEIT","A","INVALID_ACTION"));
            try(var p=writer.prepareStatement("UPDATE ranked_games SET status='COMPLETED',snapshot=?::jsonb WHERE match_id=?")){p.setString(1,PersistenceJson.mapper().writeValueAsString(finished));p.setObject(2,id);p.executeUpdate();}
            var a=pair.a();var b=pair.b();new dev.battlecode.botmatch.rating.RatingService(fixture.fixture.db).confirm(writer,new dev.battlecode.botmatch.rating.ConfirmedMatchResult(id,MatchmakingService.MODE,MatchmakingService.MODE,new dev.battlecode.botmatch.rating.ConfirmedMatchResult.Bot(a.botId(),a.ownerId(),a.version(),a.sourceSha256()),new dev.battlecode.botmatch.rating.ConfirmedMatchResult.Bot(b.botId(),b.ownerId(),b.version(),b.sourceSha256()),"A","INVALID_ACTION",true,java.time.Instant.now()));
            writer.commit();var old=pending.get(5,java.util.concurrent.TimeUnit.SECONDS);assertEquals("RUNNING",old.status());assertTrue(old.ratingChanges().isEmpty());var fresh=read.match(a.ownerId(),id);assertEquals("COMPLETED",fresh.status());assertEquals(2,fresh.ratingChanges().size());
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS));}
    }
}
