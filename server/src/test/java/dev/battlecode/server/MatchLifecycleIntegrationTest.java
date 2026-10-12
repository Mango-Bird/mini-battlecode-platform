package dev.battlecode.server;

import dev.battlecode.client.DefaultTcpClient;
import dev.battlecode.network.NetworkConfig;
import dev.battlecode.protocol.*;
import dev.battlecode.rulesapi.*;
import dev.battlecode.rulesimpl.RoomOnlyRule;
import dev.battlecode.competition.*;
import dev.battlecode.competition.MatchException;
import org.junit.jupiter.api.*;
import java.io.*;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class MatchLifecycleIntegrationTest {
    RoomTcpServer server;
    FakeMatchRule sequential,simultaneous;
    final List<DefaultTcpClient> clients=new ArrayList<>();
    @BeforeEach void start()throws Exception{
        sequential=new FakeMatchRule("fake-sequential",false,2);simultaneous=new FakeMatchRule("fake-simultaneous",true,4);
        server=new RoomTcpServer(new NetworkConfig("127.0.0.1",0,32,2000,5000,1000),new InMemoryRuleRegistry(List.of(sequential,simultaneous,new RoomOnlyRule())));server.start();
    }
    @AfterEach void close()throws Exception{
        for(var client:clients)client.close();server.close();
        for(var client:clients)assertTrue(client.awaitTermination(2,TimeUnit.SECONDS));
        assertTrue(server.isTerminated());assertEquals(0,server.sessionCount());assertTrue(server.lobby().rooms().isEmpty());
    }
    DefaultTcpClient client()throws IOException{var client=new DefaultTcpClient();clients.add(client);client.connect("127.0.0.1",server.localPort());assertEquals(Command.ACK,client.hello("Test").command());return client;}
    String room(DefaultTcpClient host,String rule,int maxTurns,int timeout)throws IOException{
        var ack=host.request(Command.CREATE_ROOM,Map.of("ruleId",rule,"maxPlayers","2","maxTurns",Integer.toString(maxTurns),"turnTimeoutMillis",Integer.toString(timeout)));
        assertEquals(Command.ACK,ack.command());return ack.payload().get("roomId");
    }
    String room(DefaultTcpClient host,DefaultTcpClient guest,String rule)throws IOException{String id=room(host,rule,100,3000);assertEquals(Command.ACK,guest.request(Command.JOIN_ROOM,Map.of("roomId",id)).command());return id;}
    Message start(DefaultTcpClient client,String room)throws IOException{return client.request(Command.START_MATCH,Map.of("roomId",room));}
    String match(DefaultTcpClient host,String room)throws IOException{Message response=start(host,room);assertEquals(Command.ACK,response.command());return response.payload().get("matchId");}
    Message action(DefaultTcpClient player,String match,int turn)throws IOException{return player.request(Command.ACTION,Map.of("matchId",match,"turn",Integer.toString(turn),"action.move","ok"));}
    Message event(DefaultTcpClient client,String type)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(System.nanoTime()<deadline){var event=client.pollEvent(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);if(event.isPresent() && type.equals(event.get().payload().get("type")))return event.get();}
        throw new AssertionError("Missing event "+type);
    }
    @Test void nonHostCannotStart()throws Exception{var a=client();var b=client();String room=room(a,b,sequential.id);assertEquals(ErrorCode.NOT_OWNER,start(b,room).errorCode());assertEquals(RoomStatus.WAITING,server.lobby().rooms().getFirst().status());}
    @Test void insufficientPlayersCannotStart()throws Exception{var a=client();String room=room(a,sequential.id,10,1000);assertEquals(ErrorCode.INVALID_STATE,start(a,room).errorCode());assertEquals(RoomStatus.WAITING,server.lobby().rooms().getFirst().status());}
    @Test void failedInitializationLeavesRoomWaitingAndCanRetry()throws Exception{var a=client();var b=client();String room=room(a,b,sequential.id);sequential.explodeInitial=true;assertEquals(ErrorCode.INTERNAL_ERROR,start(a,room).errorCode());assertEquals(RoomStatus.WAITING,server.lobby().rooms().getFirst().status());sequential.explodeInitial=false;assertEquals(Command.ACK,start(a,room).command());}
    @Test void roomOnlyDescriptorCannotStart()throws Exception{var a=client();var b=client();String room=room(a,b,RoomOnlyRule.ID);assertEquals(ErrorCode.INVALID_STATE,start(a,room).errorCode());assertTrue(server.matches().result(UUID.randomUUID()).isEmpty());}
    @Test void startsMatchAndPublishesPrivateStartedViews()throws Exception{var a=client();var b=client();String id=match(a,room(a,b,sequential.id));for(var client:List.of(a,b)){var event=event(client,"STARTED");assertEquals(Command.MATCH_EVENT,event.command());assertEquals("",event.requestId());assertEquals(client.sessionId(),event.sessionId());assertEquals(client.sessionId(),event.payload().get("view.self"));assertEquals(id,event.payload().get("matchId"));assertEquals(Set.of("type","matchId","turn","deadlineEpochMillis","view.self","view.count","view.rule"),event.payload().keySet());}assertEquals(RoomStatus.RUNNING,server.lobby().rooms().getFirst().status());}
    @Test void sequentialTcpDemoFinishesAndClosesRoom()throws Exception{var a=client();var b=client();String id=match(a,room(a,b,sequential.id));event(a,"STARTED");assertEquals("0",action(a,id,0).payload().get("turn"));assertEquals("1",event(b,"STATE").payload().get("view.count"));assertEquals(Command.ACK,action(b,id,1).command());var finished=event(a,"FINISHED");assertEquals(a.sessionId(),finished.payload().get("winners"));assertEquals("FAKE_FINISHED",finished.payload().get("reason"));assertEquals(Set.of("type","matchId","turn","winners","reason"),finished.payload().keySet());event(a,"CLOSED");assertTrue(server.lobby().rooms().isEmpty());assertEquals(Command.ACK,a.request(Command.CREATE_ROOM,Map.of("ruleId",RoomOnlyRule.ID,"maxPlayers","2","maxTurns","10","turnTimeoutMillis","1000")).command());}
    @Test void rejectsDuplicateStart()throws Exception{var a=client();var b=client();String room=room(a,b,sequential.id);String id=match(a,room);assertEquals(ErrorCode.INVALID_STATE,start(a,room).errorCode());assertEquals(id,event(b,"STARTED").payload().get("matchId"));}
    @Test void concurrentStartsCreateOneMatch()throws Exception{var a=client();var b=client();String room=room(a,b,sequential.id);var manager=server.matches();UUID host=UUID.fromString(a.sessionId());var results=race(()->tryStart(manager,host,room),()->tryStart(manager,host,room));assertEquals(1,results.stream().filter(Objects::nonNull).count());assertEquals(RoomStatus.RUNNING,server.lobby().rooms().getFirst().status());}
    private UUID tryStart(MatchManager manager,UUID host,String room){try{return manager.startMatch(host,UUID.fromString(room));}catch(MatchException ex){assertEquals(MatchException.Reason.INVALID_STATE,ex.reason());return null;}}
    @Test void actionBeforeStartRejected()throws Exception{var a=client();assertEquals(ErrorCode.INVALID_STATE,action(a,UUID.randomUUID().toString(),0).errorCode());}
    @Test void actionAfterFinishRejected()throws Exception{var a=client();var b=client();String id=match(a,room(a,b,sequential.id));action(a,id,0);action(b,id,1);assertEquals(ErrorCode.INVALID_STATE,action(a,id,2).errorCode());}
    @Test void actionForAnotherMatchRejected()throws Exception{var a=client();var b=client();String id=match(a,room(a,b,sequential.id));var c=client();var d=client();match(c,room(c,d,simultaneous.id));assertEquals(ErrorCode.INVALID_STATE,action(c,id,0).errorCode());}
    @Test void wrongPlayerCannotAct()throws Exception{var a=client();var b=client();String id=match(a,room(a,b,sequential.id));assertEquals(ErrorCode.NOT_YOUR_TURN,action(b,id,0).errorCode());}
    @Test void invalidRulePayloadRejected()throws Exception{var a=client();var b=client();String id=match(a,room(a,b,sequential.id));assertEquals(ErrorCode.ACTION_REJECTED,a.request(Command.ACTION,Map.of("matchId",id,"turn","0","action.move","bad")).errorCode());}
    @Test void staleTurnRejected()throws Exception{var a=client();var b=client();String id=match(a,room(a,b,sequential.id));action(a,id,0);assertEquals(ErrorCode.INVALID_STATE,action(b,id,0).errorCode());assertTrue(server.matches().result(UUID.fromString(id)).isEmpty());}
    @Test void concurrentSimultaneousActionsApplyBoth()throws Exception{var a=client();var b=client();String id=match(a,room(a,b,simultaneous.id));var results=race(()->action(a,id,0),()->action(b,id,0));assertEquals(2,results.stream().filter(m->m.command()==Command.ACK).count());Message state;do{state=event(a,"STATE");}while(!state.payload().get("view.count").equals("2"));assertEquals("1",state.payload().get("turn"));}
    @Test void samePlayerCannotDoubleSubmitSharedTurn()throws Exception{var a=client();var b=client();String id=match(a,room(a,b,simultaneous.id));assertEquals(Command.ACK,action(a,id,0).command());assertEquals(ErrorCode.NOT_YOUR_TURN,action(a,id,0).errorCode());assertEquals(Command.ACK,action(b,id,0).command());}
    @Test void leaveAndJoinRunningRoomRejected()throws Exception{var a=client();var b=client();var c=client();String room=room(a,b,sequential.id);match(a,room);assertEquals(ErrorCode.INVALID_STATE,a.request(Command.LEAVE_ROOM,Map.of()).errorCode());assertEquals(ErrorCode.INVALID_STATE,c.request(Command.JOIN_ROOM,Map.of("roomId",room)).errorCode());}
    @Test void hostDisconnectTransfersOwnershipAndRuleContinues()throws Exception{var a=client();var b=client();String id=match(a,room(a,b,sequential.id));event(b,"STARTED");a.close();event(b,"LEFT");Room room=server.lobby().rooms().getFirst();assertEquals(UUID.fromString(b.sessionId()),room.ownerId());assertEquals(RoomStatus.RUNNING,room.status());assertTrue(server.matches().result(UUID.fromString(id)).isEmpty());}
    @Test void ruleCanFinishOnDisconnect()throws Exception{sequential.finishOnDisconnect=true;var a=client();var b=client();String id=match(a,room(a,b,sequential.id));a.close();event(b,"FINISHED");event(b,"CLOSED");assertEquals("FAKE_FINISHED",server.matches().result(UUID.fromString(id)).orElseThrow().reason());}
    @Test void ruleExceptionCancelsWithoutAffectingOtherRoom()throws Exception{var a=client();var b=client();String bad=match(a,room(a,b,sequential.id));var c=client();var d=client();String good=match(c,room(c,d,simultaneous.id));sequential.explode=true;assertEquals(ErrorCode.INTERNAL_ERROR,action(a,bad,0).errorCode());assertEquals("RULE_ERROR",event(b,"CANCELLED").payload().get("reason"));assertEquals(Command.ACK,action(c,good,0).command());}
    @Test void maxTurnsCancels()throws Exception{var a=client();var b=client();String room=room(a,sequential.id,1,3000);b.request(Command.JOIN_ROOM,Map.of("roomId",room));String id=match(a,room);assertEquals(Command.ACK,action(a,id,0).command());assertEquals("MAX_TURNS",event(b,"CANCELLED").payload().get("reason"));}
    @Test void schedulerProcessesTurnTimeout()throws Exception{var a=client();var b=client();String room=room(a,sequential.id,10,100);b.request(Command.JOIN_ROOM,Map.of("roomId",room));String id=match(a,room);assertEquals("1",event(a,"STATE").payload().get("turn"));event(b,"FINISHED");assertTrue(server.matches().result(UUID.fromString(id)).isPresent());}
    @Test void shutdownCancelsRunningMatchesAndTerminatesThreads()throws Exception{var a=client();var b=client();String id=match(a,room(a,b,simultaneous.id));server.close();assertEquals("SERVER_SHUTDOWN",server.matches().result(UUID.fromString(id)).orElseThrow().reason());assertTrue(server.isTerminated());}
    @Test void shutdownActionRaceCannotReverseTerminalState()throws Exception{var a=client();var b=client();String id=match(a,room(a,b,simultaneous.id));race(()->{try{action(a,id,0);}catch(IOException ignored){}return true;},()->{server.close();return true;});assertEquals("SERVER_SHUTDOWN",server.matches().result(UUID.fromString(id)).orElseThrow().reason());assertTrue(server.isTerminated());}
    @Test void ruleAndConfigIsolationAcrossRooms()throws Exception{var a=client();var b=client();String first=match(a,room(a,b,sequential.id));var c=client();var d=client();String secondRoom=room(c,simultaneous.id,1,3000);d.request(Command.JOIN_ROOM,Map.of("roomId",secondRoom));String second=match(c,secondRoom);action(a,first,0);action(c,second,0);action(d,second,0);assertEquals("MAX_TURNS",server.matches().result(UUID.fromString(second)).orElseThrow().reason());assertTrue(server.matches().result(UUID.fromString(first)).isEmpty());assertEquals(Command.ACK,action(b,first,1).command());}
    @Test void spoofedSessionRejectedBeforeAction()throws Exception{var a=client();var b=client();String id=match(a,room(a,b,sequential.id));var codec=new LengthPrefixedMessageCodec();try(var socket=new Socket("127.0.0.1",server.localPort())){socket.setSoTimeout(2000);codec.write(socket.getOutputStream(),new Message("1.0","hello","",Command.HELLO,ErrorCode.NONE,Map.of("name","spoof")));codec.read(socket.getInputStream()).orElseThrow();codec.write(socket.getOutputStream(),new Message("1.0","act",a.sessionId(),Command.ACTION,ErrorCode.NONE,Map.of("matchId",id,"turn","0","action.move","ok")));assertEquals(ErrorCode.INVALID_SESSION,codec.read(socket.getInputStream()).orElseThrow().errorCode());}assertTrue(server.matches().result(UUID.fromString(id)).isEmpty());}
    @Test void repeatedActionRequestIdIsRejectedWithoutDoubleApply()throws Exception{
        var guest=client();var codec=new LengthPrefixedMessageCodec();
        try(var socket=new Socket("127.0.0.1",server.localPort())){
            socket.setSoTimeout(2000);
            String session=exchange(socket,codec,new Message("1.0","h","",Command.HELLO,ErrorCode.NONE,Map.of("name","raw-host"))).sessionId();
            String room=exchange(socket,codec,new Message("1.0","c",session,Command.CREATE_ROOM,ErrorCode.NONE,Map.of("ruleId",sequential.id,"maxPlayers","2","maxTurns","10","turnTimeoutMillis","3000"))).payload().get("roomId");
            guest.request(Command.JOIN_ROOM,Map.of("roomId",room));
            String id=exchange(socket,codec,new Message("1.0","s",session,Command.START_MATCH,ErrorCode.NONE,Map.of("roomId",room))).payload().get("matchId");
            var action=new Message("1.0","a",session,Command.ACTION,ErrorCode.NONE,Map.of("matchId",id,"turn","0","action.move","ok"));
            assertEquals(Command.ACK,exchange(socket,codec,action).command());
            assertEquals(ErrorCode.INVALID_PAYLOAD,exchange(socket,codec,action).errorCode());
            assertTrue(server.matches().result(UUID.fromString(id)).isEmpty());
            assertEquals("1",event(guest,"STATE").payload().get("view.count"));
            assertEquals(Command.ACK,action(guest,id,1).command());
        }
    }
    @Test void lastDisconnectCancelsAndCleansRoom()throws Exception{
        var a=client();var b=client();String id=match(a,room(a,b,simultaneous.id));a.close();event(b,"LEFT");b.close();
        var scheduler=Executors.newSingleThreadScheduledExecutor();var done=new CompletableFuture<Void>();
        try{scheduler.scheduleAtFixedRate(()->{if(server.matches().result(UUID.fromString(id)).isPresent())done.complete(null);},0,5,TimeUnit.MILLISECONDS);done.get(3,TimeUnit.SECONDS);}
        finally{scheduler.shutdownNow();assertTrue(scheduler.awaitTermination(2,TimeUnit.SECONDS));}
        assertEquals("NO_CONNECTED_PLAYERS",server.matches().result(UUID.fromString(id)).orElseThrow().reason());assertTrue(server.lobby().rooms().isEmpty());
    }
    private Message exchange(Socket socket,MessageCodec codec,Message request)throws IOException{
        codec.write(socket.getOutputStream(),request);
        for(int i=0;i<20;i++){Message response=codec.read(socket.getInputStream()).orElseThrow();if(response.requestId().equals(request.requestId()))return response;}
        throw new IOException("Response not found within finite event budget");
    }
    private <T> List<T> race(Callable<T> first,Callable<T> second)throws Exception{var executor=Executors.newVirtualThreadPerTaskExecutor();var barrier=new CyclicBarrier(3);try{var a=executor.submit(()->{barrier.await(2,TimeUnit.SECONDS);return first.call();});var b=executor.submit(()->{barrier.await(2,TimeUnit.SECONDS);return second.call();});barrier.await(2,TimeUnit.SECONDS);return Arrays.asList(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS));}finally{executor.shutdownNow();assertTrue(executor.awaitTermination(2,TimeUnit.SECONDS));}}
}
