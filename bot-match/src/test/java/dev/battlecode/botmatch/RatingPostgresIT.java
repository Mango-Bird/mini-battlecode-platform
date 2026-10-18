package dev.battlecode.botmatch;
import dev.battlecode.botmatch.persistence.*;
import dev.battlecode.botmatch.rating.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
@Timeout(60)
class RatingPostgresIT {
    Database admin,db;String schema;ConfirmedMatchResult.Bot a,b;RatingService service;
    static final Instant TIME=Instant.parse("2026-10-14T12:00:00Z");
    @BeforeEach void setup(){
        String url=required("BATTLECODE_TEST_DB_URL"),user=required("BATTLECODE_TEST_DB_USER"),password=required("BATTLECODE_TEST_DB_PASSWORD");
        admin=new Database(url,user,password);schema="rating_test_"+UUID.randomUUID().toString().replace("-","");admin.transaction(c->{try(var s=c.createStatement()){s.execute("CREATE SCHEMA "+schema);}return null;});
        db=new Database(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,user,password);db.migrate();db.acquireLease();var auth=new AuthService(db);var store=new PostgresSubmissionRepository(db);
        UUID ownerA=auth.register("alice","long-local-test-password").id(),ownerB=auth.register("bob","long-local-test-password").id();
        var botA=store.createOwned(ownerA,UUID.randomUUID(),"AliceBot","function chooseMove(o){return o.legalMoves[0];}");var botB=store.createOwned(ownerB,UUID.randomUUID(),"BobBot","function chooseMove(o){return o.legalMoves[0];}");
        a=new ConfirmedMatchResult.Bot(botA.metadata().botId(),ownerA,1,botA.metadata().sourceSha256());b=new ConfirmedMatchResult.Bot(botB.metadata().botId(),ownerB,1,botB.metadata().sourceSha256());service=new RatingService(db,RatingPolicy.V1,Clock.fixed(TIME,ZoneOffset.UTC));
    }
    static String required(String n){String v=System.getenv(n);if(v==null)throw new IllegalStateException("Real PostgreSQL required: "+n);return v;}
    @AfterEach void cleanup(){if(db!=null)db.close();if(admin!=null&&schema!=null){assertTrue(schema.matches("rating_test_[a-f0-9]{32}"));admin.transaction(c->{try(var s=c.createStatement()){s.execute("DROP SCHEMA "+schema+" CASCADE");}return null;});admin.close();}}
    ConfirmedMatchResult result(UUID id,String outcome,boolean ranked){return new ConfirmedMatchResult(id,"ott-board-9x9-v1","ott-board-9x9-v1",a,b,outcome,"CONFIRMED",ranked,TIME);}
    int count(String table){return db.transaction(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM "+table)){r.next();return r.getInt(1);}});}
    @Test void duplicateConcurrentRestartAndPeriod()throws Exception{
        var r=result(UUID.randomUUID(),"A",true);var pool=Executors.newFixedThreadPool(4);
        try{var jobs=pool.invokeAll(List.<Callable<Boolean>>of(()->service.confirm(r),()->service.confirm(r),()->service.confirm(r),()->service.confirm(result(UUID.randomUUID(),"DRAW",true))));int admitted=0;for(var j:jobs)if(j.get())admitted++;assertEquals(2,admitted);}finally{pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
        assertEquals(2,count("match_scoring_ledger"));assertEquals(4,count("rating_events"));assertFalse(new RatingService(db,RatingPolicy.V1,Clock.fixed(TIME,ZoneOffset.UTC)).confirm(r));
        assertThrows(IllegalStateException.class,()->service.confirm(result(r.matchId(),"B",true)));
        var next=new RatingService(db,RatingPolicy.V1,Clock.fixed(TIME.plusSeconds(86400),ZoneOffset.UTC));next.closeDue();assertEquals(2,count("rating_period_updates"));next.closeDue();assertEquals(2,count("rating_period_updates"));assertEquals(2,next.leaderboard("ott-board-9x9-v1","ott-board-9x9-v1",false).size());
        var before=next.leaderboard("ott-board-9x9-v1","ott-board-9x9-v1",false);
        db.transaction(c->{try(var s=c.createStatement()){s.execute("UPDATE rating_profiles SET elo=42,rating=99,wins=99");}return null;});next.rebuildProfiles();assertEquals(before,next.leaderboard("ott-board-9x9-v1","ott-board-9x9-v1",false));
        new RatingService(db,RatingPolicy.V1,Clock.fixed(TIME.plusSeconds(172800),ZoneOffset.UTC)).closeDue();assertEquals(4,count("rating_period_updates"));
        assertThrows(Database.Unavailable.class,()->db.transaction(c->{try(var s=c.createStatement()){s.execute("UPDATE rating_events SET score=0");}return null;}));
    }
    @Test void unrankedCancelledOwnerAndModeIsolation(){
        service.confirm(result(UUID.randomUUID(),"A",false));service.confirm(result(UUID.randomUUID(),"CANCELLED",true));
        service.confirm(new ConfirmedMatchResult(UUID.randomUUID(),"ott-board-9x9-v1","ott-board-9x9-v1",a,a,"A","SELF",true,TIME));
        service.confirm(new ConfirmedMatchResult(UUID.randomUUID(),"rps-v1","rps-v1",a,b,"A","WIN",true,TIME));service.confirm(new ConfirmedMatchResult(UUID.randomUUID(),"ott-board-9x9-v1","ott-board-9x9-v2",a,b,"A","WIN",true,TIME));assertEquals(5,count("match_scoring_ledger"));assertEquals(0,count("rating_events"));assertEquals(0,count("rating_profiles"));
    }
    @Test void sourceUpgradeKeepsIdentityAndRollback(){service.confirm(result(UUID.randomUUID(),"A",true));new PostgresSubmissionRepository(db).saveOwned(a.ownerId(),UUID.randomUUID(),a.botId(),1,"AliceBot","function chooseMove(o){return o.legalMoves[0];}");var upgraded=new ConfirmedMatchResult.Bot(a.botId(),a.ownerId(),2,a.sourceSha256());service.confirm(new ConfirmedMatchResult(UUID.randomUUID(),"ott-board-9x9-v1","ott-board-9x9-v1",upgraded,b,"B","WIN",true,TIME));assertEquals(2,count("rating_profiles"));
        var r=result(UUID.randomUUID(),"DRAW",true);assertThrows(IllegalStateException.class,()->db.transaction(c->{service.confirm(c,r);throw new IllegalStateException("rollback");}));assertEquals(2,count("match_scoring_ledger"));}
}
