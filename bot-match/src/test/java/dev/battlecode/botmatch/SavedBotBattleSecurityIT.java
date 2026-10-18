package dev.battlecode.botmatch;

import dev.battlecode.botrunner.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(180)
class SavedBotBattleSecurityIT {
    final ObjectMapper json=new ObjectMapper();
    LocalArenaService service; LocalArenaHttpServer server; HttpClient client;
    VerifiedWorkerRunner worker;
    @BeforeEach void open() throws Exception {
        worker=new VerifiedWorkerRunner(new VerifiedWorkerRunner.Config(Path.of(System.getProperty("battlecode.worker.python")),
            Path.of("../isolated-worker/worker.py"),Path.of(System.getProperty("battlecode.worker.docker")),
            Files.readString(Path.of("../.tools/phase8f/image-id.txt")).trim()));
        assertTrue(worker.capabilities().executable(),worker.capabilities().toString());
        service=new LocalArenaService(Path.of("../.tools/quickjs/qjs.exe"),worker);
        server=new LocalArenaHttpServer(true,0,Set.of("http://127.0.0.1:5173"),service,true);server.start();
        client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }
    @AfterEach void close(){if(client!=null)client.close();if(server!=null)server.close();else if(service!=null)service.close();}
    HttpResponse<String> call(String path,String method,Object body,String token) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.port()+"/api/local/"+path))
            .timeout(Duration.ofSeconds(10)).header("Origin","http://127.0.0.1:5173");
        if(token!=null)builder.header("X-Battlecode-Workspace",token);
        if(body!=null)builder.header("Content-Type","application/json");
        builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    String token() throws Exception {return json.readTree(call("workspace-session","GET",null,null).body()).get("workspaceToken").asText();}
    JsonNode create(String name,String source,String token) throws Exception {
        var response=call("submissions","POST",Map.of("requestId",UUID.randomUUID(),"name",name,"source",source),token);
        assertEquals(201,response.statusCode(),response.body());return json.readTree(response.body());
    }
    Map<String,Object> selection(JsonNode detail) {var m=detail.get("metadata");return Map.of("botId",m.get("botId").asText(),"version",1,"sourceSha256",m.get("sourceSha256").asText());}
    JsonNode finished(String id) throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(100).toNanos();JsonNode state;
        do {state=json.readTree(call("battles/"+id,"GET",null,null).body());if(!state.get("status").asText().equals("RUNNING"))return state;Thread.sleep(100);}while(System.nanoTime()<deadline);
        fail("Battle did not terminate within deadline");return null;
    }
    @Test void savedVersionsAreFrozenPrivateAndReplayIsAuthoritative() throws Exception {
        String owner=token(),opponent=token();
        String first="function chooseMove(o){if(o.turn>4)return {};return o.legalMoves[0];}";
        var a=create("User A",first,owner);var b=create("User B","function chooseMove(o){return o.legalMoves[0];}",opponent);
        String idA=a.get("metadata").get("botId").asText();
        assertEquals("EXECUTABLE",a.get("metadata").get("executionEligibility").asText());
        assertEquals(403,call("submissions/"+idA,"GET",null,opponent).statusCode());
        assertEquals(403,call("submissions/"+idA,"GET",null,null).statusCode());
        var catalog=call("bots","GET",null,null).body();assertFalse(catalog.contains("chooseMove"));assertFalse(catalog.contains("sourceBase64"));
        var request=Map.of("requestId",UUID.randomUUID(),"botA",selection(a),"botB",selection(b));
        var start=call("battles","POST",request,owner);assertEquals(201,start.statusCode(),start.body());
        String battle=json.readTree(start.body()).get("id").asText();
        assertEquals(battle,json.readTree(call("battles","POST",request,owner).body()).get("id").asText());
        assertEquals(409,call("battles","POST",Map.of("requestId",UUID.randomUUID(),"botA",selection(a),"botB",selection(b)),owner).statusCode());
        var save=call("submissions/"+idA,"PUT",Map.of("requestId",UUID.randomUUID(),"expectedVersion",1,"name","User A","source","while(true){}"),owner);
        assertEquals(200,save.statusCode());
        var state=finished(battle);assertEquals("FINISHED",state.get("status").asText());
        assertEquals("FORFEIT",state.get("result").get("outcome").asText());assertEquals("B",state.get("result").get("winner").asText());
        assertEquals(4,state.get("moves").size());assertEquals(5,state.get("frames").size());
        assertEquals(state.get("board"),state.get("frames").get(4));
        assertEquals(state.get("moves"),json.readTree(call("battles/"+battle+"/moves","GET",null,null).body()).get("moves"));
        assertTrue(state.get("botA").asText().contains(":v1:"));assertFalse(state.toString().contains("chooseMove"));
        var tampered=new HashMap<>(selection(a));tampered.put("sourceSha256","0".repeat(64));
        assertEquals(400,call("battles","POST",Map.of("requestId",UUID.randomUUID(),"botA",tampered,"botB",selection(b)),owner).statusCode());
    }
    @Test void gracefulShutdownCancelsUserBattleWithoutBlamingBot() throws Exception {
        String token=token();var a=create("A","function chooseMove(o){return o.legalMoves[0];}",token);
        var response=call("battles","POST",Map.of("requestId",UUID.randomUUID(),"botA",selection(a),"botB",selection(a)),token);
        assertEquals(201,response.statusCode());UUID id=UUID.fromString(json.readTree(response.body()).get("id").asText());
        server.close();assertEquals("CANCELLED",service.get(id).status());assertEquals("CANCELLED",service.get(id).result().outcome());
    }
    @Test void actualSupervisorCrashCancelsBattleAndRevokesEligibility() throws Exception {
        String token=token();var a=create("Crash test","function chooseMove(o){return o.legalMoves[0];}",token);
        var response=call("battles","POST",Map.of("requestId",UUID.randomUUID(),"botA",selection(a),"botB",selection(a)),token);
        assertEquals(201,response.statusCode());String id=json.readTree(response.body()).get("id").asText();
        long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();
        while(service.get(UUID.fromString(id)).moves().isEmpty()&&System.nanoTime()<deadline)Thread.sleep(50);
        assertFalse(service.get(UUID.fromString(id)).moves().isEmpty());
        // Fault injection into this test's own supervisor; no runtime bypass or public control endpoint.
        var field=VerifiedWorkerRunner.class.getDeclaredField("process");field.setAccessible(true);
        ((Process)field.get(worker)).destroyForcibly();
        var finalState=finished(id);assertEquals("CANCELLED",finalState.get("status").asText());
        assertEquals("CANCELLED",finalState.get("result").get("outcome").asText());assertTrue(finalState.get("result").get("winner").isNull());
        assertFalse(json.readTree(call("execution-capabilities","GET",null,null).body()).get("executable").asBoolean());
        assertEquals("NOT_EXECUTABLE",json.readTree(call("submissions/"+a.get("metadata").get("botId").asText()+"/eligibility","GET",null,token).body()).get("executionEligibility").asText());
    }
}
