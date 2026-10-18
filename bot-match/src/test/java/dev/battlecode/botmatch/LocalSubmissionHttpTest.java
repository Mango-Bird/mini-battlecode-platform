package dev.battlecode.botmatch;
import org.junit.jupiter.api.*;
import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@Timeout(15)
class LocalSubmissionHttpTest {
    final ObjectMapper json=new ObjectMapper();LocalArenaHttpServer server;HttpClient client;
    @BeforeEach void open() throws Exception {
        // No native runtime exists at this path: submission operations must not run a guest.
        server=new LocalArenaHttpServer(true,0,Set.of("http://127.0.0.1:5173"),new LocalArenaService(Path.of("NO-RUNTIME")));
        server.start();client=HttpClient.newHttpClient();
    }
    @AfterEach void close() {client.close();server.close();}
    HttpResponse<String> call(String path,String method,Object body) throws Exception {
        byte[] bytes=body instanceof byte[] raw?raw:body==null?new byte[0]:json.writeValueAsBytes(body);
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.port()+"/api/local/"+path))
            .timeout(Duration.ofSeconds(3)).header("Origin","http://127.0.0.1:5173").header("Content-Type","application/json")
            .method(method,HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),HttpResponse.BodyHandlers.ofString());
    }
    Map<String,Object> create(String source) {return Map.of("requestId",UUID.randomUUID().toString(),"name","User","source",source);}
    @Test void CRUDValidateAndArenaEligibility() throws Exception {
        var body=create("function chooseMove(o) { return o.legalMoves[0]; }");
        var response=call("submissions","POST",body);assertEquals(201,response.statusCode(),response.body());
        var first=json.readTree(response.body());String id=first.get("metadata").get("botId").asText();
        assertEquals(id,json.readTree(call("submissions","POST",body).body()).get("metadata").get("botId").asText());
        var saved=call("submissions/"+id,"PUT",Map.of("requestId",UUID.randomUUID().toString(),"expectedVersion",1,"name","Rename","source","new source"));
        assertEquals(200,saved.statusCode(),saved.body());assertEquals(2,json.readTree(saved.body()).get("versions").size());
        assertEquals(409,call("submissions/"+id,"PUT",Map.of("requestId",UUID.randomUUID().toString(),"expectedVersion",1,"name","Race","source","other")).statusCode());
        var validated=call("submissions/"+id+"/validate","POST",Map.of("requestId",UUID.randomUUID().toString(),"expectedVersion",2));
        assertEquals("NOT_VERIFIED",json.readTree(validated.body()).get("runtimeVerification").asText());
        var list=json.readTree(call("submissions","GET",null).body());assertFalse(list.toString().contains("source\""));
        var catalog=json.readTree(call("bots","GET",null).body());
        assertEquals("NOT_EXECUTABLE",catalog.get("submissions").get(0).get("executionEligibility").asText());
        assertEquals(400,call("battles","POST",Map.of("requestId",UUID.randomUUID().toString(),"botA","submission:"+id,"botB","CapturePriorityBot")).statusCode());
        assertEquals(200,call("submissions/"+id,"GET",null).statusCode());
        assertEquals(200,call("submissions/"+id,"DELETE",Map.of("requestId",UUID.randomUUID().toString(),"expectedVersion",2)).statusCode());
        assertEquals(404,call("submissions/"+id,"GET",null).statusCode());
    }
    @Test void rejectsMalformedEncodingSchemaAndSourceWithoutLeak() throws Exception {
        assertEquals(400,call("submissions","POST",new byte[]{(byte)0xff}).statusCode());
        var extra=new HashMap<>(create("PRIVATE_MARKER"));extra.put("execute",true);
        var response=call("submissions","POST",extra);assertEquals(400,response.statusCode());assertFalse(response.body().contains("PRIVATE_MARKER"));
        assertEquals(400,call("submissions","POST",create("é".repeat(16385))).statusCode());
        assertEquals(413,call("submissions","POST",new byte[210001]).statusCode());
        String duplicate="{\"requestId\":\""+UUID.randomUUID()+"\",\"name\":\"A\",\"name\":\"B\",\"source\":\"x\"}";
        assertEquals(400,call("submissions","POST",duplicate.getBytes(java.nio.charset.StandardCharsets.UTF_8)).statusCode());
    }
}
