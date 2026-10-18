package dev.battlecode.botmatch;
import dev.battlecode.botrunner.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual saved bots, actual verified worker, actual HTTP tournament; no mocked execution. */
@Timeout(150)
class TournamentWorkerSecurityIT {
    final ObjectMapper json=new ObjectMapper();LocalArenaHttpServer server;LocalArenaService arena;HttpClient client;
    VerifiedWorkerRunner worker;
    @BeforeEach void open() throws Exception {
        worker=new VerifiedWorkerRunner(new VerifiedWorkerRunner.Config(Path.of(System.getProperty("battlecode.worker.python")),Path.of("../isolated-worker/worker.py"),
            Path.of(System.getProperty("battlecode.worker.docker")),Files.readString(Path.of("../.tools/phase8f/image-id.txt")).trim()));
        assertTrue(worker.capabilities().executable(),worker.capabilities().toString());arena=new LocalArenaService(Path.of("../.tools/quickjs/qjs.exe"),worker);
        server=new LocalArenaHttpServer(true,0,Set.of("http://127.0.0.1:5173"),arena,true);server.start();client=HttpClient.newHttpClient();
    }
    @AfterEach void close(){if(client!=null)client.close();if(server!=null)server.close();else if(arena!=null)arena.close();}
    HttpResponse<String> call(String path,String method,Object data,String token) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.port()+"/api/local/"+path)).timeout(Duration.ofSeconds(10)).header("Origin","http://127.0.0.1:5173");
        if(token!=null)builder.header("X-Battlecode-Workspace",token);if(data!=null)builder.header("Content-Type","application/json");
        return client.send(builder.method(method,data==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(data))).build(),HttpResponse.BodyHandlers.ofString());
    }
    String token() throws Exception{return json.readTree(call("workspace-session","GET",null,null).body()).get("workspaceToken").asText();}
    Map<String,Object> mutation(){return Map.of("requestId",UUID.randomUUID());}
    JsonNode bot(String token,String name) throws Exception {
        var r=call("submissions","POST",Map.of("requestId",UUID.randomUUID(),"name",name,"source","function chooseMove(o){if(o.turn>1)return {};return o.legalMoves[0];}"),token);assertEquals(201,r.statusCode());return json.readTree(r.body()).get("metadata");
    }
    Object registration(JsonNode bot){return Map.of("requestId",UUID.randomUUID(),"botId",bot.get("botId").asText(),"version",1,"sourceSha256",bot.get("sourceSha256").asText());}
    @Test void actualRoundRobinFrozenSourcesSharedAdmissionPrivacyAndScores() throws Exception {
        String a=token(),b=token();JsonNode botA=bot(a,"A"),botB=bot(b,"B");
        var create=Map.of("requestId",UUID.randomUUID(),"name","Real RR","format","ROUND_ROBIN","maxParticipants",2,"registrationDeadline",(Object)"2030-01-01T00:00:00Z","seed",42,"maxDrawRematches",1);
        var response=call("tournaments","POST",create,a);assertEquals(201,response.statusCode(),response.body());String id=json.readTree(response.body()).get("tournamentId").asText();String path="tournaments/"+id;
        assertEquals(id,json.readTree(call("tournaments","POST",create,a).body()).get("tournamentId").asText());
        assertEquals(403,call(path+"/open","POST",mutation(),b).statusCode());assertEquals(200,call(path+"/open","POST",mutation(),a).statusCode());
        assertEquals(403,call(path+"/register","POST",registration(botA),b).statusCode());
        assertEquals(400,call(path+"/register","POST",Map.of("requestId",UUID.randomUUID(),"botId",botA.get("botId").asText(),"version",1,"sourceSha256","0".repeat(64)),a).statusCode());
        var register=registration(botA);assertEquals(200,call(path+"/register","POST",register,a).statusCode());assertEquals(200,call(path+"/register","POST",register,a).statusCode());
        assertEquals(200,call(path+"/register","POST",registration(botB),b).statusCode());
        var forged=new HashMap<String,Object>();forged.put("requestId",UUID.randomUUID());forged.put("ownerId",a);
        assertEquals(400,call(path+"/close","POST",forged,a).statusCode());
        assertEquals(200,call("submissions/"+botA.get("botId").asText(),"PUT",Map.of("requestId",UUID.randomUUID(),"expectedVersion",1,"name","A","source","function chooseMove(o){return {}; }"),a).statusCode());
        assertEquals(200,call(path+"/close","POST",mutation(),a).statusCode());assertEquals(409,call(path+"/register","POST",registration(botB),b).statusCode());
        var start=mutation();assertEquals(200,call(path+"/start","POST",start,a).statusCode());assertEquals(200,call(path+"/start","POST",start,a).statusCode());
        assertEquals(409,call("battles","POST",Map.of("requestId",UUID.randomUUID(),"botA","FirstLegalMoveBot","botB","CapturePriorityBot"),a).statusCode());
        JsonNode state=null;long deadline=System.nanoTime()+Duration.ofSeconds(40).toNanos();
        do {state=json.readTree(call(path,"GET",null,b).body()).get("tournament");if(!state.get("status").asText().equals("RUNNING"))break;Thread.sleep(100);}while(System.nanoTime()<deadline);
        assertEquals("FINISHED",state.get("status").asText(),state.toString());assertFalse(state.toString().contains("chooseMove"));assertFalse(state.toString().contains("sourceBase64"));assertFalse(state.toString().contains("runtimeFingerprint"));
        assertEquals(2,state.get("rounds").size());for(var standing:state.get("standings")){assertEquals(3,standing.get("points").asInt());assertEquals(2,standing.get("played").asInt());assertEquals(1,standing.get("forfeits").asInt());}
        for(var round:state.get("rounds")){String match=round.get("matches").get(0).get("matchId").asText();var replay=json.readTree(call(path+"/matches/"+match,"GET",null,b).body());assertEquals(1,replay.get("moves").size());assertEquals(2,replay.get("frames").size());assertEquals(replay.get("board"),replay.get("frames").get(1));}
        assertEquals(200,call(path+"/matches","GET",null,b).statusCode());assertEquals(200,call(path+"/bracket","GET",null,b).statusCode());assertEquals(200,call(path+"/standings","GET",null,b).statusCode());
        assertEquals(403,call(path,"GET",null,null).statusCode());
        var later=arena.start("FirstLegalMoveBot","CapturePriorityBot",UUID.randomUUID());assertNotNull(later.id());
    }
    String prepared(String owner,String format,int count) throws Exception {
        var config=new HashMap<String,Object>();config.put("requestId",UUID.randomUUID());config.put("name","Real "+format);config.put("format",format);config.put("maxParticipants",4);config.put("seed",7);config.put("maxDrawRematches",1);
        var response=call("tournaments","POST",config,owner);assertEquals(201,response.statusCode());String id=json.readTree(response.body()).get("tournamentId").asText();String path="tournaments/"+id;
        assertEquals(200,call(path+"/open","POST",mutation(),owner).statusCode());
        for(int i=0;i<count;i++){String participant=token();var saved=bot(participant,"Entry "+i);assertEquals(200,call(path+"/register","POST",registration(saved),participant).statusCode());}
        assertEquals(200,call(path+"/close","POST",mutation(),owner).statusCode());return id;
    }
    JsonNode await(String id,String owner) throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(40).toNanos();JsonNode state;
        do{state=json.readTree(call("tournaments/"+id,"GET",null,owner).body()).get("tournament");if(!state.get("status").asText().equals("RUNNING"))return state;Thread.sleep(100);}while(System.nanoTime()<deadline);
        fail("Tournament did not finish within deadline");return null;
    }
    @Test void actualEliminationUsesByeAndSameIsolatedAdmission() throws Exception {
        String owner=token(),id=prepared(owner,"SINGLE_ELIMINATION",3);assertEquals(200,call("tournaments/"+id+"/start","POST",mutation(),owner).statusCode());
        var state=await(id,owner);assertEquals("FINISHED",state.get("status").asText());assertFalse(state.get("result").get("championId").isNull());
        assertEquals(2,state.get("rounds").size());int byes=0,finished=0;
        for(var round:state.get("rounds"))for(var match:round.get("matches")){if(match.get("status").asText().equals("BYE"))byes++;else if(match.get("status").asText().equals("FINISHED"))finished++;}
        assertEquals(1,byes);assertEquals(2,finished);
    }
    @Test void realInfrastructureLossStopsSchedulingWithoutScores() throws Exception {
        String owner=token(),id=prepared(owner,"ROUND_ROBIN",2);assertEquals(200,call("tournaments/"+id+"/start","POST",mutation(),owner).statusCode());
        worker.close();var state=await(id,owner);assertEquals("CANCELLED",state.get("status").asText());assertTrue(state.get("result").get("championId").isNull());
        for(var row:state.get("standings")){assertEquals(0,row.get("points").asInt());assertEquals(0,row.get("played").asInt());}
    }
}
