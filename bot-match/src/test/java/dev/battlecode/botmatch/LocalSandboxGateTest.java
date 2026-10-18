package dev.battlecode.botmatch;
import org.junit.jupiter.api.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class LocalSandboxGateTest {
    @Test void missingSandboxIsNotExecutableAndDoesNotFallbackToFixture() throws Exception {
        var json=new ObjectMapper();
        try(var arena=new LocalArenaService(Path.of("../.tools/quickjs/qjs.exe"));
            var server=new LocalArenaHttpServer(true,0,Set.of("http://127.0.0.1:5173"),arena,true);
            var client=HttpClient.newHttpClient()) {
            server.start();String base="http://127.0.0.1:"+server.port()+"/api/local/";
            var response=client.send(HttpRequest.newBuilder(URI.create(base+"execution-capabilities"))
                .header("Origin","http://127.0.0.1:5173").timeout(Duration.ofSeconds(3)).build(),HttpResponse.BodyHandlers.ofString());
            assertFalse(json.readTree(response.body()).get("executable").asBoolean());
            var saved=Map.of("botId",UUID.randomUUID(),"version",1,"sourceSha256","0".repeat(64));
            var denied=client.send(HttpRequest.newBuilder(URI.create(base+"battles")).timeout(Duration.ofSeconds(3))
                .header("Origin","http://127.0.0.1:5173").header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("requestId",UUID.randomUUID(),"botA",saved,"botB","FirstLegalMoveBot"))))
                .build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(409,denied.statusCode());assertTrue(denied.body().contains("refused"));
        }
    }
}
