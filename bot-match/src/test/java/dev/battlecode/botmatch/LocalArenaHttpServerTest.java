package dev.battlecode.botmatch;
import org.junit.jupiter.api.*;
import com.fasterxml.jackson.databind.*;
import dev.battlecode.rulesimpl.board.Board;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(45)
class LocalArenaHttpServerTest {
    LocalArenaHttpServer server;
    LocalArenaService service;
    HttpClient client;
    final ObjectMapper json=new ObjectMapper();
    @BeforeEach void open() throws Exception {
        service=new LocalArenaService(Path.of("../.tools/quickjs/qjs.exe"));
        server=new LocalArenaHttpServer(true,0,Set.of("http://127.0.0.1:5173"),service);server.start();
        client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }
    @AfterEach void close() {if(client!=null)client.close();if(server!=null)server.close();}
    HttpResponse<String> request(String path,String body,String origin) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.port()+"/api/local/"+path)).timeout(Duration.ofSeconds(10));
        if(origin!=null)request.header("Origin",origin);
        if(body!=null)request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void requiresLocalConfigurationAndExactOrigin() throws Exception {
        assertThrows(IllegalArgumentException.class,()->new LocalArenaHttpServer(false,0,Set.of("http://127.0.0.1:5173"),service));
        assertThrows(IllegalArgumentException.class,()->new LocalArenaHttpServer(true,0,Set.of("*"),service));
        assertEquals(403,request("bots",null,null).statusCode());
        assertEquals(403,request("bots",null,"https://attacker.example").statusCode());
        var catalog=request("bots",null,"http://127.0.0.1:5173");
        assertEquals(200,catalog.statusCode());assertEquals(12,json.readTree(catalog.body()).get("initialBoard").size());
        assertEquals(2,json.readTree(catalog.body()).get("bots").size());
    }
    @Test void rejectsSourceAndUnknownBot() throws Exception {
        String id=UUID.randomUUID().toString();
        assertEquals(400,request("battles","{\"botA\":\"FirstLegalMoveBot\",\"botB\":\"CapturePriorityBot\",\"requestId\":\""+id+"\",\"source\":\"evil\"}","http://127.0.0.1:5173").statusCode());
        assertEquals(400,request("battles","{\"botA\":\"InfiniteLoopBot\",\"botB\":\"CapturePriorityBot\",\"requestId\":\""+id+"\"}","http://127.0.0.1:5173").statusCode());
        assertEquals(413,request("battles","x".repeat(2049),"http://127.0.0.1:5173").statusCode());
    }
    @Test void actualQuickJsBattleHasAuthoritativeReplayAndIdempotentStart() throws Exception {
        String origin="http://127.0.0.1:5173";
        String body=json.writeValueAsString(Map.of("botA","FirstLegalMoveBot","botB","CapturePriorityBot","requestId",UUID.randomUUID().toString()));
        var response=request("battles",body,origin);assertEquals(201,response.statusCode(),response.body());
        String id=json.readTree(response.body()).get("id").asText();
        assertEquals(id,json.readTree(request("battles",body,origin).body()).get("id").asText());
        var busy=request("battles",body.replace(json.readTree(body).get("requestId").asText(),UUID.randomUUID().toString()),origin);
        assertEquals(409,busy.statusCode());
        JsonNode state=null;
        long deadline=System.nanoTime()+Duration.ofSeconds(35).toNanos();
        do {
            state=json.readTree(request("battles/"+id,null,origin).body());
            if(!state.get("status").asText().equals("RUNNING"))break;
            Thread.sleep(100);
        }while(System.nanoTime()<deadline);
        assertEquals("FINISHED",state.get("status").asText(),state.toString());
        assertEquals("DRAW",state.get("result").get("outcome").asText());
        assertEquals(200,state.get("moves").size());
        assertEquals(201,state.get("frames").size());
        assertEquals(state.get("board"),state.get("frames").get(200));
        var history=json.readTree(request("battles/"+id+"/moves",null,origin).body());
        assertEquals(state.get("moves"),history.get("moves"));
        assertFalse(state.toString().contains("source"));assertFalse(state.toString().contains("executionId"));
        assertEquals(12,state.get("frames").get(0).size());
    }
    @Test void shutdownStopsListener() throws Exception {
        int port=server.port();server.close();
        assertThrows(Exception.class,()->client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/local/bots")).timeout(Duration.ofSeconds(2)).build(),HttpResponse.BodyHandlers.ofString()));
    }
    @Test void shutdownCancelsActiveBattleWithoutForfeit() throws Exception {
        var snapshot=service.start("FirstLegalMoveBot","CapturePriorityBot",UUID.randomUUID());
        server.close();
        assertEquals("CANCELLED",service.get(snapshot.id()).status());
        assertEquals("CANCELLED",service.get(snapshot.id()).result().outcome());
    }
}
