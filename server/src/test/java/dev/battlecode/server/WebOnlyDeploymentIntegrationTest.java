package dev.battlecode.server;

import dev.battlecode.network.NetworkConfig;
import dev.battlecode.rulesapi.InMemoryRuleRegistry;
import dev.battlecode.rulesimpl.*;
import org.junit.jupiter.api.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class WebOnlyDeploymentIntegrationTest {
    RoomTcpServer server;
    HttpClient http;
    ServerSocket occupiedTcp;
    int webPort;
    List<WebMultiplayerIntegrationTest.Peer> peers=new ArrayList<>();
    @BeforeEach void start() throws Exception {
        // Reserving the configured TCP port proves WEB_ONLY never starts a TCP listener.
        occupiedTcp=new ServerSocket(0,1,InetAddress.getLoopbackAddress());
        http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        server=new RoomTcpServer(new NetworkConfig("127.0.0.1",occupiedTcp.getLocalPort(),16,1500,5000,1000),
            new InMemoryRuleRegistry(List.of(new RoomOnlyRule(),new RockPaperScissorsRule(),new NumberGuessingRule(bound->41))),
            new WebSocketConfig("0.0.0.0",0,Set.of("https://frontend.example"),32,1000),ServerMode.WEB_ONLY);
        server.start();
        webPort=server.websocketPort();
    }
    @AfterEach void stop() throws Exception {
        peers.forEach(WebMultiplayerIntegrationTest.Peer::close);
        server.close();http.close();occupiedTcp.close();
        assertTrue(server.isTerminated());assertEquals(0,server.sessionCount());assertEquals(0,server.connectionCount());
    }
    URI uri(String path) {return URI.create((path.equals("/ws")?"ws":"http")+"://127.0.0.1:"+webPort+path);}
    WebMultiplayerIntegrationTest.Peer peer(String name) throws Exception {
        var p=new WebMultiplayerIntegrationTest.Peer();peers.add(p);
        p.ws=http.newWebSocketBuilder().header("Origin","https://frontend.example").buildAsync(uri("/ws"),p).get(3,TimeUnit.SECONDS);
        p.identity=p.request("HELLO",Map.of("name",name)).path("sessionId").asText();p.room();return p;
    }
    @Test void startupHealthHandshakeAndNoTcpListener() throws Exception {
        assertThrows(IllegalStateException.class,server::localPort);
        var health=http.send(HttpRequest.newBuilder(uri("/health")).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,health.statusCode());assertEquals("{\"status\":\"ok\"}",health.body());
        assertEquals("ACK",peer("A").request("PING",Map.of()).path("command").asText());
    }
    @Test void rejectsMissingAndInvalidOriginWith403() {
        for(String origin:List.of("", "https://evil.example")) {
            var builder=http.newWebSocketBuilder();if(!origin.isEmpty())builder.header("Origin",origin);
            var ex=assertThrows(ExecutionException.class,()->builder.buildAsync(uri("/ws"),new WebMultiplayerIntegrationTest.Peer()).get(3,TimeUnit.SECONDS));
            assertEquals(403,((WebSocketHandshakeException)ex.getCause()).getResponse().statusCode());
        }
    }
    @Test void twoBrowserSessionsRpsShareRoomAndShutdownIsGraceful() throws Exception {
        var a=peer("A");var b=peer("B");String room=create(a,"rock-paper-scissors",Map.of("option.roundsToWin","1"));
        b.request("JOIN_ROOM",Map.of("roomId",room));a.room();b.room();String match=startMatch(a,b,room);
        a.request("ACTION",Map.of("matchId",match,"turn","0","action.choice","ROCK"));
        assertEquals("",b.event("STATE").path("payload").path("view.yourChoice").asText());
        b.request("ACTION",Map.of("matchId",match,"turn","0","action.choice","SCISSORS"));
        assertEquals(a.identity,a.event("FINISHED").path("payload").path("winners").asText());assertTrue(a.room().path("room").isNull());
        server.close();server.close();assertStopped(a,b);
    }
    @Test void numberGuessingUsesSameProductionRuleInWebOnly() throws Exception {
        var a=peer("A");var b=peer("B");String room=create(a,"number-guessing",Map.of());
        b.request("JOIN_ROOM",Map.of("roomId",room));a.room();b.room();String match=startMatch(a,b,room);
        a.request("ACTION",Map.of("matchId",match,"turn","0","action.guess","43"));
        assertEquals("LOWER",b.event("STATE").path("payload").path("view.history.0.hint").asText());
        b.request("ACTION",Map.of("matchId",match,"turn","1","action.guess","42"));
        assertEquals("CORRECT_GUESS;secret=42",a.event("FINISHED").path("payload").path("reason").asText());
    }
    @Test void shutdownCancelsRunningMatchAndReleasesBothSessions() throws Exception {
        var a=peer("A");var b=peer("B");String room=create(a,"rock-paper-scissors",Map.of());
        b.request("JOIN_ROOM",Map.of("roomId",room));a.room();b.room();startMatch(a,b,room);
        server.close();assertStopped(a,b);
        assertTrue(server.lobby().rooms().isEmpty());assertTrue(server.isTerminated());
    }
    void assertStopped(WebMultiplayerIntegrationTest.Peer a,WebMultiplayerIntegrationTest.Peer b) throws Exception {
        // Abrupt transport closure can surface as EOF/error rather than onClose in JDK HttpClient.
        assertThrows(Exception.class,()->a.request("PING",Map.of()));
        assertThrows(Exception.class,()->b.request("PING",Map.of()));
        assertThrows(java.io.IOException.class,()->http.send(HttpRequest.newBuilder(uri("/health"))
            .timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.ofString()));
        assertTrue(server.isTerminated());assertEquals(0,server.sessionCount());assertEquals(0,server.connectionCount());
    }
    String create(WebMultiplayerIntegrationTest.Peer a,String rule,Map<String,String> options) throws Exception {
        var p=new HashMap<String,String>(Map.of("ruleId",rule,"maxPlayers","2","maxTurns","20","turnTimeoutMillis","3000"));p.putAll(options);
        String room=a.request("CREATE_ROOM",p).path("payload").path("roomId").asText();a.room();return room;
    }
    String startMatch(WebMultiplayerIntegrationTest.Peer a,WebMultiplayerIntegrationTest.Peer b,String room) throws Exception {
        String id=a.request("START_MATCH",Map.of("roomId",room)).path("payload").path("matchId").asText();a.room();b.room();a.event("STARTED");b.event("STARTED");return id;
    }
}
