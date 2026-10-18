package dev.battlecode.server;

import dev.battlecode.client.DefaultTcpClient;
import dev.battlecode.network.NetworkConfig;
import dev.battlecode.protocol.*;
import dev.battlecode.rulesapi.InMemoryRuleRegistry;
import dev.battlecode.rulesimpl.*;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class ProductionGameRulesIntegrationTest {
    private RoomTcpServer server;
    private final List<DefaultTcpClient> clients = new ArrayList<>();

    @BeforeEach void start() throws Exception {
        var registry = new InMemoryRuleRegistry(List.of(new RoomOnlyRule(), new RockPaperScissorsRule(),
                new NumberGuessingRule(bound -> 41)));
        server = new RoomTcpServer(new NetworkConfig("127.0.0.1",0,32,2000,5000,1000),registry);
        server.start();
    }

    @AfterEach void close() throws Exception {
        for (var client : clients) client.close();
        server.close();
        for (var client : clients) assertTrue(client.awaitTermination(2,TimeUnit.SECONDS));
        assertTrue(server.isTerminated());
    }

    private DefaultTcpClient client() throws IOException {
        var client = new DefaultTcpClient(); clients.add(client);
        client.connect("127.0.0.1",server.localPort());
        assertEquals(Command.ACK,client.hello("Production-test").command());
        return client;
    }

    private String createRoom(DefaultTcpClient host,String rule,int maxTurns,int timeout,Map<String,String> options) throws IOException {
        var payload = new HashMap<String,String>();
        payload.put("ruleId",rule); payload.put("maxPlayers","2"); payload.put("maxTurns",Integer.toString(maxTurns));
        payload.put("turnTimeoutMillis",Integer.toString(timeout));
        options.forEach((key,value)->payload.put("option."+key,value));
        Message result=host.request(Command.CREATE_ROOM,payload);
        assertEquals(Command.ACK,result.command(),result.payload().toString());
        return result.payload().get("roomId");
    }

    private void join(DefaultTcpClient guest,String room) throws IOException {
        assertEquals(Command.ACK,guest.request(Command.JOIN_ROOM,Map.of("roomId",room)).command());
    }

    private String startMatch(DefaultTcpClient host,String room) throws IOException {
        Message result=host.request(Command.START_MATCH,Map.of("roomId",room));
        assertEquals(Command.ACK,result.command(),result.payload().toString());
        return result.payload().get("matchId");
    }

    private Message action(DefaultTcpClient player,String match,int turn,Map<String,String> fields) throws IOException {
        var payload=new HashMap<String,String>();payload.put("matchId",match);payload.put("turn",Integer.toString(turn));
        fields.forEach((key,value)->payload.put("action."+key,value));
        return player.request(Command.ACTION,payload);
    }

    private Message event(DefaultTcpClient client,String type) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(System.nanoTime()<deadline){
            var event=client.pollEvent(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
            if(event.isPresent()&&type.equals(event.get().payload().get("type")))return event.get();
        }
        throw new AssertionError("Missing event "+type);
    }

    @Test void defaultProductionCompositionListsAllRegisteredRules() throws Exception {
        var defaults=new RoomTcpServer(new NetworkConfig("127.0.0.1",0,8,2000,5000,1000));
        try {
            defaults.start();
            try(var client=new DefaultTcpClient()) {
                client.connect("127.0.0.1",defaults.localPort());
                assertEquals(Command.ACK,client.hello("Rule-list").command());
                var response=client.request(Command.LIST_RULES,Map.of());
                assertEquals(Set.of("room-only-no-gameplay","rock-paper-scissors","number-guessing","ott-board-9x9-v1"),
                        response.payload().entrySet().stream().filter(entry->entry.getKey().matches("rule\\.\\d+\\.id"))
                                .map(Map.Entry::getValue).collect(java.util.stream.Collectors.toSet()));
            }
        } finally { defaults.close(); }
    }

    @Test void rockPaperScissorsTcpMatchHidesChoiceThenRevealsAndFinishes() throws Exception {
        var a=client();var b=client();String room=createRoom(a,RockPaperScissorsRule.ID,10,3000,Map.of("roundsToWin","1"));join(b,room);
        String match=startMatch(a,room);event(a,"STARTED");event(b,"STARTED");
        assertEquals(Command.ACK,action(a,match,0,Map.of("choice","ROCK")).command());
        Message privateA=event(a,"STATE"),privateB=event(b,"STATE");
        assertEquals("ROCK",privateA.payload().get("view.yourChoice"));
        assertEquals("",privateB.payload().get("view.yourChoice"));
        assertFalse(privateB.payload().containsValue("ROCK"));
        assertEquals(ErrorCode.NOT_YOUR_TURN,action(a,match,0,Map.of("choice","PAPER")).errorCode());
        assertEquals(Command.ACK,action(b,match,0,Map.of("choice","SCISSORS")).command());
        Message finishedA=event(a,"FINISHED"),finishedB=event(b,"FINISHED");
        for(Message finished:List.of(finishedA,finishedB)){
            assertEquals(a.sessionId(),finished.payload().get("winners"));
            assertTrue(finished.payload().get("reason").contains("choices=ROCK,SCISSORS"));
        }
        event(a,"CLOSED");assertTrue(server.lobby().rooms().isEmpty());
    }

    @Test void numberGuessingTcpTurnsHintsAndTerminalSecret() throws Exception {
        var a=client();var b=client();String room=createRoom(a,NumberGuessingRule.ID,10,3000,Map.of());join(b,room);
        String match=startMatch(a,room);event(a,"STARTED");event(b,"STARTED");
        assertEquals(ErrorCode.NOT_YOUR_TURN,action(b,match,0,Map.of("guess","42")).errorCode());
        assertEquals(Command.ACK,action(a,match,0,Map.of("guess","43")).command());
        Message stateA=event(a,"STATE");assertEquals("LOWER",stateA.payload().get("view.history.0.hint"));
        Message stateB=event(b,"STATE");
        assertEquals("LOWER",stateB.payload().get("view.history.0.hint"));
        assertEquals(b.sessionId(),stateB.payload().get("view.currentPlayer"));
        assertFalse(stateB.payload().keySet().stream().anyMatch(key->key.toLowerCase(Locale.ROOT).contains("secret")));
        assertEquals(Command.ACK,action(b,match,1,Map.of("guess","41")).command());
        stateA=event(a,"STATE");assertEquals("HIGHER",stateA.payload().get("view.history.1.hint"));
        assertEquals(Command.ACK,action(a,match,2,Map.of("guess","42")).command());
        for(Message finished:List.of(event(a,"FINISHED"),event(b,"FINISHED"))){
            assertEquals(a.sessionId(),finished.payload().get("winners"));
            assertEquals("CORRECT_GUESS;secret=42",finished.payload().get("reason"));
        }
        event(a,"CLOSED");
    }

    @Test void simultaneousTimeoutIsDrawAndNumberGuessTimeoutPassesTurn() throws Exception {
        var a=client();var b=client();String rpsRoom=createRoom(a,RockPaperScissorsRule.ID,10,300,Map.of());join(b,rpsRoom);
        String rps=startMatch(a,rpsRoom);event(a,"STARTED");event(b,"STARTED");
        Message draw=event(a,"STATE");assertEquals("1",draw.payload().get("turn"));
        assertEquals("DRAW",draw.payload().get("view.lastRoundWinner"));
        assertEquals("0",draw.payload().get("view.score."+a.sessionId()));

        var c=client();var d=client();String guessRoom=createRoom(c,NumberGuessingRule.ID,10,600,Map.of());join(d,guessRoom);
        String guessing=startMatch(c,guessRoom);event(c,"STARTED");event(d,"STARTED");
        Message passed=event(d,"STATE");assertEquals("1",passed.payload().get("turn"));
        assertEquals(d.sessionId(),passed.payload().get("view.currentPlayer"));
    }

    @Test void maximumTurnsUsesPhase4CancellationWithoutInventingWinner() throws Exception {
        var a=client();var b=client();String room=createRoom(a,NumberGuessingRule.ID,1,3000,Map.of());join(b,room);
        String match=startMatch(a,room);event(a,"STARTED");event(b,"STARTED");
        assertEquals(Command.ACK,action(a,match,0,Map.of("guess","41")).command());
        Message cancelled=event(b,"CANCELLED");assertEquals("MAX_TURNS",cancelled.payload().get("reason"));
        assertFalse(cancelled.payload().containsKey("winners"));
    }

    @Test void realMultiRoomTcpMatchesKeepEventsStateAndWinnersIsolated() throws Exception {
        var a=client();var b=client();var c=client();var d=client();
        String rpsRoom=createRoom(a,RockPaperScissorsRule.ID,10,3000,Map.of("roundsToWin","1"));join(b,rpsRoom);
        String guessRoom=createRoom(c,NumberGuessingRule.ID,10,3000,Map.of());join(d,guessRoom);
        String rps=startMatch(a,rpsRoom),guess=startMatch(c,guessRoom);
        event(a,"STARTED");event(b,"STARTED");event(c,"STARTED");event(d,"STARTED");
        assertEquals(ErrorCode.INVALID_STATE,action(a,guess,0,Map.of("guess","42")).errorCode());
        assertEquals(Command.ACK,action(a,rps,0,Map.of("choice","PAPER")).command());
        assertEquals(Command.ACK,action(c,guess,0,Map.of("guess","43")).command());
        Message rpsView=event(b,"STATE"),guessView=event(d,"STATE");
        assertEquals("",rpsView.payload().get("view.yourChoice"));
        assertEquals("LOWER",guessView.payload().get("view.history.0.hint"));
        assertFalse(rpsView.payload().keySet().stream().anyMatch(key->key.contains("history")));
        assertFalse(guessView.payload().keySet().stream().anyMatch(key->key.contains("score")));
        assertEquals(Command.ACK,action(b,rps,0,Map.of("choice","ROCK")).command());
        Message finishedRps=event(a,"FINISHED");assertEquals(a.sessionId(),finishedRps.payload().get("winners"));
        assertEquals(Command.ACK,action(d,guess,1,Map.of("guess","42")).command());
        Message finishedGuess=event(c,"FINISHED");assertEquals(d.sessionId(),finishedGuess.payload().get("winners"));
        assertTrue(server.matches().result(UUID.fromString(rps)).isPresent());
        assertTrue(server.matches().result(UUID.fromString(guess)).isPresent());
    }

    @Test void disconnectForfeitsBothProductionRulesWithoutCrossMatchEffects() throws Exception {
        var a=client();var b=client();var c=client();var d=client();
        String rps=startMatch(a,room(a,b,RockPaperScissorsRule.ID));
        String number=startMatch(c,room(c,d,NumberGuessingRule.ID));
        event(a,"STARTED");event(b,"STARTED");event(c,"STARTED");event(d,"STARTED");
        a.close();assertEquals(b.sessionId(),event(b,"FINISHED").payload().get("winners"));
        assertTrue(server.matches().result(UUID.fromString(number)).isEmpty());
        c.close();assertEquals(d.sessionId(),event(d,"FINISHED").payload().get("winners"));
    }

    private String room(DefaultTcpClient a,DefaultTcpClient b,String rule)throws IOException{
        String id=createRoom(a,rule,10,3000,Map.of());join(b,id);return id;
    }
}
