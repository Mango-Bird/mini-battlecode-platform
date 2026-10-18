package dev.battlecode.botmatch;
import dev.battlecode.botmatch.persistence.*;
import dev.battlecode.botmatch.tournament.*;
import dev.battlecode.botrunner.*;
import dev.battlecode.rulesimpl.board.Board;
import org.junit.jupiter.api.*;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.net.*;
import java.net.http.*;
import com.fasterxml.jackson.databind.*;
import static org.junit.jupiter.api.Assertions.*;
/** Explicit IT suite requires actual PostgreSQL 17. No H2/assumptions/skips. */
@Timeout(60)
public class PostgresPersistenceIT {
    Database admin,db;String schema;PostgresSubmissionRepository bots;AuthService auth;
    static final String PASSWORD="long-test-password-for-local-accounts";
    static final String SOURCE="function chooseMove(o) {\r\n // tiếng Việt\r\n return o.legalMoves[0];\r\n}\r\n";
    @BeforeEach void setup(){
        admin=new Database(required("BATTLECODE_TEST_DB_URL"),required("BATTLECODE_TEST_DB_USER"),required("BATTLECODE_TEST_DB_PASSWORD"));
        schema="phase10_test_"+UUID.randomUUID().toString().replace("-","");admin.transaction(c->{try(var s=c.createStatement()){s.execute("CREATE SCHEMA "+schema);}return null;});
        String url=required("BATTLECODE_TEST_DB_URL");db=new Database(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,required("BATTLECODE_TEST_DB_USER"),required("BATTLECODE_TEST_DB_PASSWORD"));
        db.migrate();db.acquireLease();bots=new PostgresSubmissionRepository(db);auth=new AuthService(db);
    }
    static String required(String name){String v=System.getenv(name);if(v==null||v.isBlank())throw new IllegalStateException("Real PostgreSQL test configuration required: "+name);return v;}
    @AfterEach void cleanup(){if(db!=null)db.close();if(admin!=null&&schema!=null){assertTrue(schema.matches("phase10_test_[a-f0-9]{32}"));admin.transaction(c->{try(var s=c.createStatement()){s.execute("DROP SCHEMA "+schema+" CASCADE");}return null;});admin.close();}}
    @Test void emptyMigrationConstraintsAndLease(){
        db.migrate();assertEquals(Integer.valueOf(5),db.<Integer>transaction(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM flyway_schema_history WHERE success")){r.next();return r.getInt(1);}}));
        var other=new Database(required("BATTLECODE_TEST_DB_URL")+"?currentSchema="+schema,required("BATTLECODE_TEST_DB_USER"),required("BATTLECODE_TEST_DB_PASSWORD"));
        try{assertThrows(IllegalStateException.class,other::acquireLease);}finally{other.close();}
        var owner=auth.register("alice",PASSWORD);var saved=bots.createOwned(owner.id(),UUID.randomUUID(),"Exact",SOURCE);
        assertArrayEquals(SOURCE.getBytes(StandardCharsets.UTF_8),bots.freezeVersion(saved.metadata().botId(),1,saved.metadata().sourceSha256()).source());
        assertThrows(Database.Unavailable.class,()->db.transaction(c->{try(var p=c.prepareStatement("UPDATE bot_versions SET source=? WHERE bot_id=?")){p.setBytes(1,new byte[]{1});p.setObject(2,saved.metadata().botId());p.executeUpdate();}return null;}));
    }
    @Test void passwordsOpaqueSessionsExpiryRevocationAndRestart(){
        var user=auth.register("alice",PASSWORD);assertThrows(IllegalStateException.class,()->auth.register("alice",PASSWORD));
        assertThrows(AuthService.Unauthorized.class,()->auth.login("missing",PASSWORD));assertThrows(AuthService.Unauthorized.class,()->auth.login("alice","wrong"));
        var login=auth.login("alice",PASSWORD);assertEquals(user,new AuthService(db).require(login.token()));
        db.transaction(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT password_hash FROM users")){r.next();assertFalse(r.getString(1).contains(PASSWORD));assertTrue(r.getString(1).startsWith("$2"));}
            try(var s=c.createStatement();var r=s.executeQuery("SELECT token_hash FROM user_sessions")){r.next();assertNotEquals(login.token(),r.getString(1));}return null;});
        auth.logout(login.token());assertThrows(AuthService.Unauthorized.class,()->auth.require(login.token()));
        var expired=new AuthService(db,Clock.systemUTC(),Duration.ofSeconds(-1)).login("alice",PASSWORD);assertThrows(AuthService.Unauthorized.class,()->auth.require(expired.token()));
        assertThrows(IllegalArgumentException.class,()->auth.register("Uppercase",PASSWORD));assertThrows(IllegalArgumentException.class,()->auth.register("valid","short"));
        for(int i=0;i<10;i++)auth.throttle("127.0.0.1","login");assertThrows(AuthService.RateLimited.class,()->auth.throttle("127.0.0.1","login"));
    }
    @Test void durableVersionsConcurrencyIdempotencyOwnershipAndDeletion()throws Exception{
        UUID a=auth.register("alice",PASSWORD).id(),b=auth.register("bob",PASSWORD).id(),request=UUID.randomUUID();
        var saved=bots.createOwned(a,request,"Exact",SOURCE);UUID id=saved.metadata().botId();
        assertEquals(saved,new PostgresSubmissionRepository(db).createOwned(a,request,"Exact",SOURCE));
        assertThrows(SecurityException.class,()->bots.saveOwned(b,UUID.randomUUID(),id,1,"Stolen",SOURCE));
        assertThrows(SecurityException.class,()->bots.deleteOwned(b,UUID.randomUUID(),id,1));
        var pool=Executors.newFixedThreadPool(2);try{
            var jobs=pool.<Boolean>invokeAll(List.of(()->save(a,id,"one"),()->save(a,id,"two")));assertEquals(1,jobs.stream().filter(f->{try{return f.get();}catch(Exception e){throw new RuntimeException(e);}}).count());
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
        assertEquals(2,bots.get(id).versions().size());var frozen=bots.freezeVersion(id,1,saved.metadata().sourceSha256());
        UUID saveRequest=UUID.randomUUID();var retryPool=Executors.newFixedThreadPool(2);try{var retries=retryPool.<LocalSubmissionStore.Detail>invokeAll(List.of(()->bots.saveOwned(a,saveRequest,id,2,"Same",SOURCE),()->bots.saveOwned(a,saveRequest,id,2,"Same",SOURCE)));assertEquals(retries.get(0).get(),retries.get(1).get());assertEquals(3,bots.get(id).versions().size());}finally{retryPool.shutdownNow();assertTrue(retryPool.awaitTermination(5,TimeUnit.SECONDS));}
        var delete=UUID.randomUUID();bots.deleteOwned(a,delete,id,3);assertThrows(NoSuchElementException.class,()->bots.get(id));
        assertEquals(id.toString(),new PostgresSubmissionRepository(db).deleteOwned(a,delete,id,3).get("deleted").toString());
        assertArrayEquals(frozen.source(),bots.freezeVersion(id,1,frozen.sourceSha256()).source());
        assertThrows(IllegalStateException.class,()->bots.createOwned(a,request,"Different",SOURCE));
    }
    boolean save(UUID owner,UUID id,String name){try{bots.saveOwned(owner,UUID.randomUUID(),id,1,name,SOURCE);return true;}catch(IllegalStateException e){return false;}}
    @Test void confirmedReplayAndUnfinishedAttemptRecoverWithoutChampion(){
        UUID a=auth.register("alice",PASSWORD).id(),b=auth.register("bob",PASSWORD).id();
        var botA=bots.createOwned(a,UUID.randomUUID(),"A",SOURCE);var botB=bots.createOwned(b,UUID.randomUUID(),"B",SOURCE);
        UUID id=UUID.randomUUID(),entryA=UUID.randomUUID(),entryB=UUID.randomUUID(),match1=UUID.randomUUID(),match2=UUID.randomUUID();
        var entries=List.of(new TournamentEntry(entryA,a,"alice",botA.metadata().botId(),"A",1,botA.metadata().sourceSha256(),0),new TournamentEntry(entryB,b,"bob",botB.metadata().botId(),"B",1,botB.metadata().sourceSha256(),1));
        var result=new LocalArenaService.Result("FORFEIT","A","INVALID_ACTION");
        var board=Board.initial().pieces();var replay=new LocalArenaService.Snapshot(UUID.randomUUID(),"A","B","FINISHED",1,"A",board,List.of(),List.of(board),result);
        var completed=new TournamentMatch(match1,1,0,0,entryA,entryB,"FINISHED",replay.id(),entryA,result);
        var interrupted=new TournamentMatch(match2,2,0,0,entryB,entryA,"RUNNING",UUID.randomUUID(),null,null);
        var config=new TournamentConfig("Recovery",TournamentFormat.ROUND_ROBIN,2,null,42,1);
        var tour=new Tournament(id,config,Instant.now(),a,TournamentStatus.RUNNING,entries,List.of(new TournamentRound(1,List.of(completed),List.of(entryA)),new TournamentRound(2,List.of(interrupted),List.of())),List.of(),null,"");
        var repository=new PostgresTournamentRepository(db);UUID request=UUID.randomUUID();repository.save(List.of(new TournamentRepository.Restored(tour,Map.of(match1,replay))),new TournamentRepository.Mutation(a,request,"start:"+id,tour));
        var arena=new LocalArenaService(Path.of("unused"));try(var service=new TournamentService(arena,repository,bots)){
            var recovered=service.get(id);assertEquals(TournamentStatus.CANCELLED,recovered.status());assertNull(recovered.result().championId());assertEquals("BACKEND_RESTART_INTERRUPTED",recovered.schedulingReason());
            assertEquals("FINISHED",recovered.rounds().getFirst().matches().getFirst().status());assertEquals("CANCELLED",recovered.rounds().getLast().matches().getFirst().status());assertEquals(3,recovered.standings().getFirst().points());assertEquals(replay,service.matchSnapshot(id,match1));
            assertEquals(tour,repository.previous(a,request,"start:"+id));assertThrows(IllegalStateException.class,()->repository.previous(a,request,"different"));
        }finally{arena.close();}
        assertEquals(TournamentStatus.CANCELLED,repository.load().getFirst().tournament().status());
        assertEquals(Integer.valueOf(1),db.<Integer>transaction(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM match_events")){r.next();return r.getInt(1);}}));
        var ratings=new dev.battlecode.botmatch.rating.RatingService(db);
        assertEquals(2,ratings.leaderboard("ott-board-9x9-v1","ott-board-9x9-v1",false).size());
        assertEquals(1,ratings.history(botA.metadata().botId()).size());
        assertEquals(1516.0,ratings.leaderboard("ott-board-9x9-v1","ott-board-9x9-v1",false).getFirst().get("elo"));
    }
    @Test void httpCookieCsrfAuthorizationPrivacyAndLogout()throws Exception{
        var arena=new LocalArenaService(Path.of("unused"));var mapper=PersistenceJson.mapper();
        try(var server=new LocalArenaHttpServer(true,0,Set.of("http://127.0.0.1:5173"),arena,true,db)){
            server.start();try(var client=HttpClient.newHttpClient()){
                String root="http://127.0.0.1:"+server.port()+"/api/local/";
                var signup=request(client,root,"auth/register","POST",Map.of("username","alice","password",PASSWORD),null,true);assertEquals(201,signup.statusCode());
                var login=request(client,root,"auth/login","POST",Map.of("username","alice","password",PASSWORD),null,true);assertEquals(200,login.statusCode());
                String header=login.headers().firstValue("Set-Cookie").orElseThrow();assertTrue(header.contains("HttpOnly"));assertTrue(header.contains("SameSite=Strict"));String cookie=header.split(";")[0];
                // A cookie or spoofed proxy headers must not make an untrusted Origin acceptable.
                for(String origin:List.of("https://attacker.example","null","http://127.0.0.1:5173.attacker.example")) {
                    var forged=HttpRequest.newBuilder(URI.create(root+"auth/logout")).timeout(Duration.ofSeconds(15))
                        .header("Origin",origin).header("Cookie",cookie).header("Content-Type","application/json")
                        .header("X-Forwarded-Proto","https").header("X-Forwarded-Host","trusted.example")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"));
                    var rejected=client.send(forged.build(),HttpResponse.BodyHandlers.ofString());
                    assertEquals(403,rejected.statusCode());
                    assertTrue(rejected.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
                    assertTrue(rejected.headers().firstValue("Set-Cookie").isEmpty());
                }
                var allowed=request(client,root,"auth/me","GET",null,cookie,true);
                assertEquals(200,allowed.statusCode());
                assertEquals("http://127.0.0.1:5173",allowed.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
                assertEquals("true",allowed.headers().firstValue("Access-Control-Allow-Credentials").orElseThrow());
                assertTrue(allowed.headers().firstValue("Vary").orElseThrow().contains("Origin"));
                assertEquals(401,request(client,root,"rankings","GET",null,null,true).statusCode());
                assertEquals(403,request(client,root,"rankings","GET",null,cookie,false).statusCode());
                assertEquals(200,request(client,root,"rankings","GET",null,cookie,true).statusCode());
                assertEquals(200,request(client,root,"rankings/glicko2","GET",null,cookie,true).statusCode());
                assertFalse(login.body().contains(cookie.split("=")[1]));assertEquals(200,request(client,root,"auth/me","GET",null,cookie,true).statusCode());
                assertEquals(403,request(client,root,"submissions","POST",Map.of("requestId",UUID.randomUUID(),"name","A","source",SOURCE),cookie,false).statusCode());
                var create=request(client,root,"submissions","POST",Map.of("requestId",UUID.randomUUID(),"name","A","source",SOURCE),cookie,true);assertEquals(201,create.statusCode());UUID bot=UUID.fromString(mapper.readTree(create.body()).path("metadata").path("botId").asText());
                assertEquals(401,request(client,root,"match-history","GET",null,null,true).statusCode());
                assertEquals(200,request(client,root,"match-history","GET",null,cookie,true).statusCode());
                assertEquals(200,request(client,root,"bot-profiles/"+bot,"GET",null,cookie,true).statusCode());
                assertFalse(request(client,root,"bot-profiles/"+bot,"GET",null,cookie,true).body().contains("sourceSha256"));
                request(client,root,"auth/register","POST",Map.of("username","bob","password",PASSWORD),null,true);
                var bob=request(client,root,"auth/login","POST",Map.of("username","bob","password",PASSWORD),null,true).headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
                assertEquals(404,request(client,root,"bot-profiles/"+bot,"GET",null,bob,true).statusCode());
                assertEquals(403,request(client,root,"submissions/"+bot,"GET",null,bob,true).statusCode());
                assertEquals(403,request(client,root,"submissions/"+bot,"PUT",Map.of("requestId",UUID.randomUUID(),"expectedVersion",1,"name","Stolen","source",SOURCE),bob,true).statusCode());
                assertEquals(403,request(client,root,"submissions/"+bot,"DELETE",Map.of("requestId",UUID.randomUUID(),"expectedVersion",1),bob,true).statusCode());
                assertEquals(400,request(client,root,"submissions","POST",Map.of("requestId",UUID.randomUUID(),"ownerId",UUID.randomUUID(),"name","Forged","source",SOURCE),cookie,true).statusCode());
                assertEquals(401,request(client,root,"battles","POST",Map.of("requestId",UUID.randomUUID(),"botA","FirstLegalMoveBot","botB","CapturePriorityBot"),null,true).statusCode());
                assertEquals(403,request(client,root,"auth/logout","POST",Map.of(),cookie,false).statusCode());assertEquals(200,request(client,root,"auth/me","GET",null,cookie,true).statusCode());
                assertFalse(request(client,root,"bots","GET",null,null,true).body().contains("tiếng"));
                assertFalse(request(client,root,"submissions","GET",null,bob,true).body().contains(bot.toString()));
                assertEquals(401,request(client,root,"submissions","GET",null,null,true).statusCode());
                assertEquals(200,request(client,root,"auth/logout","POST",Map.of(),cookie,true).statusCode());assertEquals(401,request(client,root,"auth/me","GET",null,cookie,true).statusCode());
            }
        }
    }
    @Test void standaloneRestartInterruptsUnknownAttemptAndDoesNotExecuteAgain(){
        var owner=auth.register("alice",PASSWORD);var arena=new LocalArenaService(Path.of("unused"));
        try{
            var service=new BattleHistoryService(db,arena);UUID request=UUID.randomUUID(),battle=UUID.randomUUID();var board=Board.initial().pieces();
            var running=new LocalArenaService.Snapshot(battle,"A","B","RUNNING",1,"A",board,List.of(),List.of(board),null);
            assertEquals(running,service.start(owner.id(),request,"pinned-exact-version",()->running));
            var restarted=new BattleHistoryService(db,arena);assertEquals("CANCELLED",restarted.get(battle).status());assertEquals("BACKEND_RESTART_INTERRUPTED",restarted.get(battle).result().reason());assertNull(restarted.get(battle).result().winner());
            assertEquals("CANCELLED",restarted.start(owner.id(),request,"pinned-exact-version",()->{fail("Lost execution must not be retried");return running;}).status());
        }finally{arena.close();}
    }
    static HttpResponse<String> request(HttpClient client,String root,String path,String method,Object body,String cookie,boolean origin)throws Exception{
        var builder=HttpRequest.newBuilder(URI.create(root+path)).timeout(Duration.ofSeconds(15));if(origin)builder.header("Origin","http://127.0.0.1:5173");if(cookie!=null)builder.header("Cookie",cookie);
        if(body!=null)builder.header("Content-Type","application/json");builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(PersistenceJson.mapper().writeValueAsString(body)));
        return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
}
