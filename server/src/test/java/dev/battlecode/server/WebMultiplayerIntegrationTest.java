package dev.battlecode.server;

import com.fasterxml.jackson.databind.*;
import dev.battlecode.client.DefaultTcpClient;
import dev.battlecode.network.NetworkConfig;
import dev.battlecode.protocol.*;
import org.junit.jupiter.api.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(25)
class WebMultiplayerIntegrationTest {
    static final ObjectMapper JSON = new ObjectMapper();
    RoomTcpServer server;
    HttpClient http;
    List<Peer> peers = new ArrayList<>();
    @BeforeEach void start() throws Exception {
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        server = new RoomTcpServer(new NetworkConfig("127.0.0.1",0,16,1000,5000,1000),
            new dev.battlecode.rulesapi.InMemoryRuleRegistry(List.of(new dev.battlecode.rulesimpl.RoomOnlyRule(),new dev.battlecode.rulesimpl.RockPaperScissorsRule(),new dev.battlecode.rulesimpl.NumberGuessingRule(bound -> 41))),
            new WebSocketConfig("127.0.0.1",0,Set.of("http://localhost:5173"),32,1000));
        server.start();
    }
    @AfterEach void stop() throws Exception {
        peers.forEach(Peer::close); server.close(); http.close();
        assertTrue(server.isTerminated()); assertEquals(0,server.sessionCount());
    }
    Peer peer(String name) throws Exception {
        Peer p = new Peer(); peers.add(p);
        p.ws = http.newWebSocketBuilder().header("Origin","http://localhost:5173")
            .buildAsync(URI.create("ws://127.0.0.1:"+server.websocketPort()+"/ws"),p).get(3,TimeUnit.SECONDS);
        if (name != null) { p.identity = p.request("HELLO",Map.of("name",name)).path("sessionId").asText(); p.room(); }
        return p;
    }
    String room(Peer p) throws Exception {
        return p.request("CREATE_ROOM",Map.of("ruleId","rock-paper-scissors","maxPlayers","2","maxTurns","20","turnTimeoutMillis","3000","option.roundsToWin","2")).path("payload").path("roomId").asText();
    }
    @Test void handshakeHealthAndOriginAllowlist() throws Exception {
        Peer p=peer("Alice"); assertFalse(p.identity.isBlank()); assertEquals("ACK",p.request("PING",Map.of()).path("command").asText());
        assertEquals(200,http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.websocketPort()+"/health")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        assertThrows(ExecutionException.class,()->http.newWebSocketBuilder().header("Origin","https://evil.example").buildAsync(URI.create("ws://127.0.0.1:"+server.websocketPort()+"/ws"),new Peer()).get(3,TimeUnit.SECONDS));
        var missing=assertThrows(ExecutionException.class,()->http.newWebSocketBuilder().buildAsync(URI.create("ws://127.0.0.1:"+server.websocketPort()+"/ws"),new Peer()).get(3,TimeUnit.SECONDS));
        assertEquals(403,((WebSocketHandshakeException)missing.getCause()).getResponse().statusCode());
    }
    @Test void roomSnapshotsHostMigrationAuthorizationAndCapturedOrdering() throws Exception {
        Peer a=peer("Same"), b=peer("Same"), outsider=peer("Outside"); String room=room(a);
        JsonNode first=a.room(); assertEquals(a.identity,first.path("room").path("hostId").asText());
        b.request("JOIN_ROOM",Map.of("roomId",room)); JsonNode joinedA=a.room(),joinedB=b.room();
        assertEquals(2,joinedA.path("room").path("players").size());
        assertEquals("ERROR",b.request("START_MATCH",Map.of("roomId",room)).path("command").asText());
        assertTrue(server.lobby().roomDetails(UUID.fromString(outsider.identity)).isEmpty());
        a.request("LEAVE_ROOM",Map.of()); JsonNode left=a.room(),migrated=b.room();
        assertTrue(left.path("room").isNull()); assertEquals(b.identity,migrated.path("room").path("hostId").asText());
        assertTrue(migrated.path("revision").asLong()>joinedB.path("revision").asLong());
        assertEquals(2,joinedA.path("room").path("players").size()); // Captured old snapshot remains unchanged.
        assertThrows(UnsupportedOperationException.class,()->server.lobby().roomDetails(UUID.fromString(b.identity)).orElseThrow().players().clear());
        assertTrue(outsider.messages.isEmpty());
    }
    @Test void crossTransportRpsPrivacyConcurrentActionsAndClosedOrder() throws Exception {
        Peer web=peer("Browser"); String room=room(web); web.room();
        try(var tcp=new DefaultTcpClient()) {
            tcp.connect("127.0.0.1",server.localPort()); tcp.hello("TCP");
            assertEquals(Command.ACK,tcp.request(Command.JOIN_ROOM,Map.of("roomId",room)).command()); web.room();
            String match=web.request("START_MATCH",Map.of("roomId",room)).path("payload").path("matchId").asText();
            JsonNode running=web.room(); assertEquals("RUNNING",running.path("room").path("status").asText()); web.event("STARTED");
            assertEquals("ACK",web.request("ACTION",Map.of("matchId",match,"turn","0","action.choice","ROCK")).path("command").asText());
            JsonNode own=web.event("STATE"); assertEquals("ROCK",own.path("payload").path("view.yourChoice").asText());
            Message other=tcpEvent(tcp,"STATE"); assertEquals("",other.payload().get("view.yourChoice")); assertFalse(other.payload().containsValue("ROCK"));
            assertEquals(Command.ACK,tcp.request(Command.ACTION,Map.of("matchId",match,"turn","0","action.choice","SCISSORS")).command());
            web.event("STATE");
            assertEquals("ERROR",web.request("ACTION",Map.of("matchId",match,"turn","0","action.choice","PAPER")).path("command").asText());
            try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
                var x=executor.submit(()->web.request("ACTION",Map.of("matchId",match,"turn","1","action.choice","ROCK")));
                var y=executor.submit(()->tcp.request(Command.ACTION,Map.of("matchId",match,"turn","1","action.choice","SCISSORS")));
                assertEquals("ACK",x.get(4,TimeUnit.SECONDS).path("command").asText()); assertEquals(Command.ACK,y.get(4,TimeUnit.SECONDS).command());
            }
            assertEquals(web.identity,web.event("FINISHED").path("payload").path("winners").asText());
            JsonNode closed=web.room(); assertTrue(closed.path("room").isNull()); assertEquals(room,closed.path("roomId").asText());
        }
    }
    @Test void handshakeTimeoutMalformedIdentityAndShutdownCleanup() throws Exception {
        Peer idle=peer(null); idle.closed.get(3,TimeUnit.SECONDS);
        Peer forged=peer("A"); forged.ws.sendText("{\"requestId\":\"spoof\",\"command\":\"PING\",\"payload\":{},\"sessionId\":\"forged\"}",true).get(2,TimeUnit.SECONDS);
        forged.closed.get(3,TimeUnit.SECONDS);
        Peer live=peer("B"); server.close(); live.closed.get(3,TimeUnit.SECONDS); assertTrue(server.isTerminated());
    }
    @Test void preHandshakeAndOversizedMessagesAreRejected() throws Exception {
        Peer p=peer(null); assertEquals("HANDSHAKE_REQUIRED",p.request("LIST_ROOMS",Map.of()).path("errorCode").asText()); p.closed.get(3,TimeUnit.SECONDS);
        Peer large=peer("Large"); large.ws.sendText("x".repeat(65537),true).get(2,TimeUnit.SECONDS); large.closed.get(3,TimeUnit.SECONDS);
    }
    @Test void numberGuessingIsSequentialPrivateAndDisconnectEndsMatch() throws Exception {
        Peer a=peer("Host"), b=peer("Guest");
        String room=a.request("CREATE_ROOM",Map.of("ruleId","number-guessing","maxPlayers","2","maxTurns","10","turnTimeoutMillis","3000")).path("payload").path("roomId").asText();a.room();
        b.request("JOIN_ROOM",Map.of("roomId",room));a.room();b.room();
        String match=a.request("START_MATCH",Map.of("roomId",room)).path("payload").path("matchId").asText();a.room();b.room();
        JsonNode start=a.event("STARTED");b.event("STARTED");
        assertEquals("true",start.path("payload").path("view.yourTurn").asText());assertFalse(start.toString().contains("secret"));
        assertEquals("ERROR",b.request("ACTION",Map.of("matchId",match,"turn","0","action.guess","43")).path("command").asText());
        assertEquals("ACK",a.request("ACTION",Map.of("matchId",match,"turn","0","action.guess","43")).path("command").asText());
        JsonNode state=a.event("STATE");b.event("STATE");assertEquals("LOWER",state.path("payload").path("view.history.0.hint").asText());assertFalse(state.toString().contains("secret"));
        b.close();assertEquals(a.identity,a.event("FINISHED").path("payload").path("winners").asText());assertTrue(a.room().path("room").isNull());
    }
    @Test void browserReceivesCancellationBeforeRoomClosed() throws Exception {
        Peer a=peer("A"),b=peer("B");
        String room=a.request("CREATE_ROOM",Map.of("ruleId","number-guessing","maxPlayers","2","maxTurns","1","turnTimeoutMillis","3000")).path("payload").path("roomId").asText();a.room();
        b.request("JOIN_ROOM",Map.of("roomId",room));a.room();b.room();
        String match=a.request("START_MATCH",Map.of("roomId",room)).path("payload").path("matchId").asText();a.room();b.room();a.event("STARTED");b.event("STARTED");
        a.request("ACTION",Map.of("matchId",match,"turn","0","action.guess","43"));
        JsonNode cancelled=a.event("CANCELLED");assertEquals("MAX_TURNS",cancelled.path("payload").path("reason").asText());
        JsonNode closed=a.room();assertTrue(closed.path("room").isNull());
        assertTrue(a.history.indexOf(cancelled)<a.history.indexOf(closed));
        assertEquals("MAX_TURNS",b.event("CANCELLED").path("payload").path("reason").asText());
    }
    static Message tcpEvent(DefaultTcpClient client,String type) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(System.nanoTime()<until){ var e=client.pollEvent(Math.max(1,until-System.nanoTime()),TimeUnit.NANOSECONDS);if(e.isPresent()&&type.equals(e.get().payload().get("type")))return e.get(); }
        throw new AssertionError("Missing TCP event "+type);
    }
    static final class Peer implements WebSocket.Listener {
        WebSocket ws; String identity; int next; StringBuilder fragment=new StringBuilder();
        BlockingQueue<JsonNode> messages=new LinkedBlockingQueue<>(256); List<JsonNode> deferred=new ArrayList<>();
        List<JsonNode> history=new CopyOnWriteArrayList<>();
        CompletableFuture<Void> closed=new CompletableFuture<>();
        public void onOpen(WebSocket socket){socket.request(1);}
        public CompletionStage<?> onText(WebSocket socket,CharSequence text,boolean last){
            try {fragment.append(text);if(last){JsonNode node=JSON.readTree(fragment.toString());history.add(node);messages.add(node);fragment.setLength(0);}}
            catch(Exception ex){closed.completeExceptionally(ex);} socket.request(1);return null;
        }
        public CompletionStage<?> onClose(WebSocket socket,int code,String reason){closed.complete(null);return null;}
        public void onError(WebSocket socket,Throwable ex){closed.complete(null);}
        JsonNode request(String command,Map<String,String> payload) throws Exception {
            String id="r"+(++next);ws.sendText(JSON.writeValueAsString(Map.of("requestId",id,"command",command,"payload",payload)),true).get(2,TimeUnit.SECONDS);
            return await(n->id.equals(n.path("requestId").asText()));
        }
        JsonNode room() throws Exception {return await(n->n.path("type").asText().equals("room"));}
        JsonNode event(String type) throws Exception{return await(n->n.path("command").asText().equals("MATCH_EVENT")&&type.equals(n.path("payload").path("type").asText()));}
        JsonNode await(Predicate<JsonNode> test) throws Exception {
            for(var it=deferred.iterator();it.hasNext();){var n=it.next();if(test.test(n)){it.remove();return n;}}
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
            while(System.nanoTime()<until){JsonNode n=messages.poll(Math.max(1,until-System.nanoTime()),TimeUnit.NANOSECONDS);if(n==null)break;if(test.test(n))return n;deferred.add(n);}
            throw new AssertionError("Expected message; deferred="+deferred);
        }
        void close(){if(ws!=null)ws.abort();}
    }
}
