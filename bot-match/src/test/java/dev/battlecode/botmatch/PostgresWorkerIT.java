package dev.battlecode.botmatch;
import dev.battlecode.botmatch.persistence.*;
import dev.battlecode.botrunner.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
/** Real PostgreSQL + verified isolated worker + existing engine, then a fresh server after restart. */
@Timeout(180)
class PostgresWorkerIT {
    Database admin,db;String schema,url;LocalArenaHttpServer server;LocalArenaService arena;HttpClient client;
    final ObjectMapper json=PersistenceJson.mapper();
    @BeforeEach void setup()throws Exception{
        admin=new Database(PostgresPersistenceIT.required("BATTLECODE_TEST_DB_URL"),PostgresPersistenceIT.required("BATTLECODE_TEST_DB_USER"),PostgresPersistenceIT.required("BATTLECODE_TEST_DB_PASSWORD"));
        schema="phase10_test_"+UUID.randomUUID().toString().replace("-","");admin.transaction(c->{try(var s=c.createStatement()){s.execute("CREATE SCHEMA "+schema);}return null;});
        url=PostgresPersistenceIT.required("BATTLECODE_TEST_DB_URL")+"?currentSchema="+schema;client=HttpClient.newHttpClient();open();
    }
    void open()throws Exception{
        db=new Database(url,PostgresPersistenceIT.required("BATTLECODE_TEST_DB_USER"),PostgresPersistenceIT.required("BATTLECODE_TEST_DB_PASSWORD"));db.migrate();db.acquireLease();
        var worker=new VerifiedWorkerRunner(new VerifiedWorkerRunner.Config(Path.of(System.getProperty("battlecode.worker.python")),Path.of("../isolated-worker/worker.py"),Path.of(System.getProperty("battlecode.worker.docker")),Files.readString(Path.of("../.tools/phase8f/image-id.txt")).trim()));
        assertTrue(worker.capabilities().executable(),"Mandatory sandbox verification failed");arena=new LocalArenaService(Path.of("../.tools/quickjs/qjs.exe"),worker);
        server=new LocalArenaHttpServer(true,0,Set.of("http://127.0.0.1:5173"),arena,true,db);server.start();
    }
    @AfterEach void close(){if(client!=null)client.close();if(server!=null)server.close();else if(arena!=null)arena.close();if(db!=null)db.close();if(admin!=null){assertTrue(schema.matches("phase10_test_[a-f0-9]{32}"));admin.transaction(c->{try(var s=c.createStatement()){s.execute("DROP SCHEMA "+schema+" CASCADE");}return null;});admin.close();}}
    HttpResponse<String> call(String path,String method,Object body,String cookie)throws Exception{return PostgresPersistenceIT.request(client,"http://127.0.0.1:"+server.port()+"/api/local/",path,method,body,cookie,true);}
    String login(String name)throws Exception{
        assertEquals(201,call("auth/register","POST",Map.of("username",name,"password",PostgresPersistenceIT.PASSWORD),null).statusCode());
        var response=call("auth/login","POST",Map.of("username",name,"password",PostgresPersistenceIT.PASSWORD),null);assertEquals(200,response.statusCode());return response.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
    }
    Map<String,Object> mutation(){return Map.of("requestId",UUID.randomUUID());}
    @Test void frozenTournamentConcurrentStartReplayAndIdempotencySurviveFreshServer()throws Exception{
        String a=login("alice"),b=login("bob");var metadata=new ArrayList<JsonNode>();
        for(String cookie:List.of(a,b)){var created=call("submissions","POST",Map.of("requestId",UUID.randomUUID(),"name","Frozen","source","function chooseMove(o){if(o.turn>1)return {};return o.legalMoves[0];}"),cookie);assertEquals(201,created.statusCode());metadata.add(json.readTree(created.body()).get("metadata"));}
        var choices=metadata.stream().map(m->Map.of("botId",m.path("botId").asText(),"version",1,"sourceSha256",m.path("sourceSha256").asText())).toList();
        var manualRequest=Map.of("requestId",UUID.randomUUID(),"botA",choices.get(0),"botB",choices.get(1));
        var manual=call("battles","POST",manualRequest,a);assertEquals(201,manual.statusCode(),manual.body());String manualId=json.readTree(manual.body()).path("id").asText();JsonNode manualFinal;long manualDeadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();
        do{manualFinal=json.readTree(call("battles/"+manualId,"GET",null,a).body());if(!manualFinal.path("status").asText().equals("RUNNING"))break;Thread.sleep(100);}while(System.nanoTime()<manualDeadline);
        assertEquals("FINISHED",manualFinal.path("status").asText());assertEquals(1,manualFinal.path("moves").size());
        UUID createRequest=UUID.randomUUID();var config=Map.of("requestId",createRequest,"name","Durable RR","format","ROUND_ROBIN","maxParticipants",2,"seed",42,"maxDrawRematches",1);
        var created=call("tournaments","POST",config,a);assertEquals(201,created.statusCode(),created.body());String id=json.readTree(created.body()).path("tournamentId").asText(),path="tournaments/"+id;
        assertEquals(403,call(path+"/open","POST",mutation(),b).statusCode());assertEquals(200,call(path+"/open","POST",mutation(),a).statusCode());
        for(int i=0;i<2;i++){var bot=metadata.get(i);assertEquals(200,call(path+"/register","POST",Map.of("requestId",UUID.randomUUID(),"botId",bot.path("botId").asText(),"version",1,"sourceSha256",bot.path("sourceSha256").asText()),i==0?a:b).statusCode());}
        // Save then tombstone latest source; registered exact v1 remains frozen through its DB foreign key.
        String botId=metadata.getFirst().path("botId").asText();assertEquals(200,call("submissions/"+botId,"PUT",Map.of("requestId",UUID.randomUUID(),"expectedVersion",1,"name","Changed","source","function chooseMove(o){return {}; }"),a).statusCode());
        assertEquals(200,call("submissions/"+botId,"DELETE",Map.of("requestId",UUID.randomUUID(),"expectedVersion",2),a).statusCode());
        assertEquals(200,call(path+"/close","POST",mutation(),a).statusCode());
        var pool=Executors.newFixedThreadPool(2);try{
            var results=pool.<Integer>invokeAll(List.of(()->call(path+"/start","POST",mutation(),a).statusCode(),()->call(path+"/start","POST",mutation(),a).statusCode()));var codes=List.of(results.get(0).get(),results.get(1).get());assertTrue(codes.contains(200));assertTrue(codes.contains(409));
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
        long deadline=System.nanoTime()+Duration.ofSeconds(40).toNanos();JsonNode state;
        do{state=json.readTree(call(path,"GET",null,a).body()).get("tournament");if(!state.path("status").asText().equals("RUNNING"))break;Thread.sleep(100);}while(System.nanoTime()<deadline);
        assertEquals("FINISHED",state.path("status").asText());assertEquals(2,state.path("rounds").size());
        for(var standing:state.path("standings")){assertEquals(3,standing.path("points").asInt());assertEquals(2,standing.path("played").asInt());}
        String match=state.path("rounds").get(0).path("matches").get(0).path("matchId").asText();String history=call(path+"/matches/"+match,"GET",null,a).body();assertEquals(1,json.readTree(history).path("moves").size());assertFalse(history.contains("chooseMove"));
        String outsider=login("charlie");assertEquals(404,call(path+"/matches/"+match,"GET",null,outsider).statusCode());
        server.close();server=null;open();
        assertEquals(200,call("auth/me","GET",null,a).statusCode());assertEquals(state,json.readTree(call(path,"GET",null,a).body()).get("tournament"));assertEquals(json.readTree(history),json.readTree(call(path+"/matches/"+match,"GET",null,b).body()));
        assertEquals(404,call("battles/"+manualId,"GET",null,b).statusCode());assertEquals(manualFinal,json.readTree(call("battles/"+manualId,"GET",null,a).body()));assertEquals(manualFinal,json.readTree(call("battles","POST",manualRequest,a).body()));assertEquals(401,call("battles","GET",null,null).statusCode());assertEquals(1,json.readTree(call("battles","GET",null,a).body()).path("battles").size());
        assertEquals(id,json.readTree(call("tournaments","POST",config,a).body()).path("tournamentId").asText());
        assertEquals(2,db.<Integer>transaction(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM tournament_matches")){r.next();return r.getInt(1);}}).intValue());
        assertEquals(4,db.<Integer>transaction(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM match_events")){r.next();return r.getInt(1);}}).intValue());
        assertEquals(401,call("submissions","GET",null,null).statusCode());assertEquals(200,call("auth/logout","POST",Map.of(),a).statusCode());assertEquals(401,call("auth/me","GET",null,a).statusCode());
    }
    @Test void rankedQueueUsesRealVerifiedWorkerAndCommitsTwoGames()throws Exception{
        String a=login("alice"),b=login("bob");
        assertEquals(401,call("ranked/queue/me","GET",null,null).statusCode());
        var bots=new ArrayList<JsonNode>();
        for(String cookie:List.of(a,b)){
            var create=call("submissions","POST",Map.of("requestId",UUID.randomUUID(),"name","RankedBot","source","function chooseMove(o){return {}; }"),cookie);assertEquals(201,create.statusCode());bots.add(json.readTree(create.body()).get("metadata"));}
        for(int i=0;i<2;i++){var bot=bots.get(i);assertEquals(201,call("ranked/queue","POST",Map.of("requestId",UUID.randomUUID(),"botId",bot.path("botId").asText(),"version",1,"sourceSha256",bot.path("sourceSha256").asText()),i==0?a:b).statusCode());}
        long deadline=System.nanoTime()+Duration.ofSeconds(60).toNanos();JsonNode ticket;
        do{ticket=json.readTree(call("ranked/queue/me","GET",null,a).body()).path("entry").path("ticket");if(Set.of("COMPLETED","INTERRUPTED").contains(ticket.path("status").asText()))break;Thread.sleep(200);}while(System.nanoTime()<deadline);
        assertEquals("COMPLETED",ticket.path("status").asText());
        var matches=json.readTree(call("ranked/matches","GET",null,a).body()).path("matches");assertEquals(2,matches.size());
        for(var match:matches){assertEquals("COMPLETED",match.path("status").asText());assertEquals("FORFEIT",match.path("snapshot").path("result").path("outcome").asText());assertEquals(2,match.path("ratingChanges").size());}
        String matchId=matches.get(0).path("matchId").asText();var viewer=call("matches/"+matchId,"GET",null,a);assertEquals(200,viewer.statusCode());var projection=json.readTree(viewer.body());assertEquals("COMPLETED",projection.path("status").asText());assertEquals(12,projection.path("snapshot").path("board").size());assertEquals(2,projection.path("ratingChanges").size());assertFalse(viewer.body().contains("sourceSha256"));assertFalse(viewer.body().contains("chooseMove"));
        String spectatorCookie=login("charlie");assertEquals(404,call("matches/"+matchId,"GET",null,spectatorCookie).statusCode());assertEquals(401,call("matches/"+matchId,"GET",null,null).statusCode());assertEquals(0,json.readTree(call("match-history","GET",null,spectatorCookie).body()).path("matches").size());
        assertEquals(4,db.<Integer>transaction(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM rating_events")){r.next();return r.getInt(1);}}).intValue());
        String before=call("ranked/matches","GET",null,a).body();server.close();server=null;open();assertEquals(json.readTree(before),json.readTree(call("ranked/matches","GET",null,a).body()));
    }
}
