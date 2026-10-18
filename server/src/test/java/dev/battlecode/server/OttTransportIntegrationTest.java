package dev.battlecode.server;
import dev.battlecode.client.DefaultTcpClient;
import dev.battlecode.network.NetworkConfig;
import dev.battlecode.protocol.*;
import dev.battlecode.rulesapi.*;
import dev.battlecode.rulesimpl.*;
import org.junit.jupiter.api.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
@Timeout(20)
class OttTransportIntegrationTest {
    RoomTcpServer server;HttpClient http;DefaultTcpClient tcp;
    final List<WebMultiplayerIntegrationTest.Peer> peers=new ArrayList<>();
    void start(ServerMode mode) throws Exception {
        var registry=new InMemoryRuleRegistry(List.of(new RoomOnlyRule(),new RockPaperScissorsRule(),new NumberGuessingRule(),new OttBoardRule()));
        server=new RoomTcpServer(new NetworkConfig("127.0.0.1",0,16,2000,10000,1000),registry,
            new WebSocketConfig("127.0.0.1",0,Set.of("https://frontend.example"),32,1000),mode);
        server.start();http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }
    @AfterEach void close() throws Exception {
        peers.forEach(WebMultiplayerIntegrationTest.Peer::close);
        if(tcp!=null)tcp.close();if(server!=null)server.close();if(http!=null)http.close();
        if(tcp!=null)assertTrue(tcp.awaitTermination(2,TimeUnit.SECONDS));
        if(server!=null){assertTrue(server.isTerminated());assertEquals(0,server.sessionCount());}
    }
    WebMultiplayerIntegrationTest.Peer peer(String name) throws Exception {
        var peer=new WebMultiplayerIntegrationTest.Peer();peers.add(peer);
        peer.ws=http.newWebSocketBuilder().header("Origin","https://frontend.example")
            .buildAsync(URI.create("ws://127.0.0.1:"+server.websocketPort()+"/ws"),peer).get(3,TimeUnit.SECONDS);
        peer.identity=peer.request("HELLO",Map.of("name",name)).path("sessionId").asText();peer.room();return peer;
    }
    Map<String,String> create(){return Map.of("ruleId",OttBoardRule.ID,"maxPlayers","2","maxTurns","200","turnTimeoutMillis","5000");}
    @Test void tcpAndWebsocketShareOttRoomWithoutWireChanges() throws Exception {
        start(ServerMode.LOCAL);tcp=new DefaultTcpClient();tcp.connect("127.0.0.1",server.localPort());tcp.hello("A");
        var guest=peer("B");String room=tcp.request(Command.CREATE_ROOM,create()).payload().get("roomId");
        assertEquals("ACK",guest.request("JOIN_ROOM",Map.of("roomId",room)).path("command").asText());guest.room();
        var started=tcp.request(Command.START_MATCH,Map.of("roomId",room));assertEquals(Command.ACK,started.command());
        String match=started.payload().get("matchId");guest.room();var event=guest.event("STARTED");
        assertEquals("B",event.path("payload").path("view.playerId").asText());assertEquals("1",event.path("payload").path("view.turn").asText());
        assertEquals("NOT_YOUR_TURN",guest.request("ACTION",Map.of("matchId",match,"turn","0","action.pieceId","B-R1","action.to.row","6","action.to.col","6")).path("errorCode").asText());
        assertEquals(Command.ACK,tcp.request(Command.ACTION,Map.of("matchId",match,"turn","0","action.pieceId","A-S2","action.to.row","2","action.to.col","2")).command());
        assertEquals("2",guest.event("STATE").path("payload").path("view.turn").asText());
        assertEquals("ACK",guest.request("ACTION",Map.of("matchId",match,"turn","1","action.pieceId","B-R1","action.to.row","6.5","action.to.col","6")).path("command").asText());
        var finish=guest.event("FINISHED");assertEquals("FORFEIT",finish.path("payload").path("reason").asText());
        assertEquals(tcp.sessionId(),finish.path("payload").path("winners").asText());assertTrue(guest.room().path("room").isNull());
        assertTrue(server.lobby().rooms().isEmpty());
    }
    @Test void twoWebOnlyPeersPlayOttAndBadMoveForfeitsWithoutViewerOrRunner() throws Exception {
        start(ServerMode.WEB_ONLY);assertThrows(IllegalStateException.class,server::localPort);
        var a=peer("A");var b=peer("B");String room=a.request("CREATE_ROOM",create()).path("payload").path("roomId").asText();a.room();
        b.request("JOIN_ROOM",Map.of("roomId",room));a.room();b.room();
        String match=a.request("START_MATCH",Map.of("roomId",room)).path("payload").path("matchId").asText();a.room();b.room();a.event("STARTED");b.event("STARTED");
        a.request("ACTION",Map.of("matchId",match,"turn","0","action.pieceId","A-S2","action.to.row","2","action.to.col","2"));
        a.event("STATE");b.event("STATE");
        b.request("ACTION",Map.of("matchId",match,"turn","1","action.pieceId","B-R1","action.to.row","7","action.to.col","7"));
        var finish=a.event("FINISHED");assertEquals(a.identity,finish.path("payload").path("winners").asText());
        assertEquals("FORFEIT",finish.path("payload").path("reason").asText());assertTrue(a.room().path("room").isNull());
    }
}
