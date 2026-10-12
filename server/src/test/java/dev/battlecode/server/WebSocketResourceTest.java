package dev.battlecode.server;

import dev.battlecode.network.*;
import dev.battlecode.protocol.*;
import org.eclipse.jetty.websocket.api.Session;
import org.junit.jupiter.api.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class WebSocketResourceTest {
    @Test void boundedQueueDisconnectsSlowWriterAndReleasesResources() throws Exception {
        var config = new WebSocketConfig("127.0.0.1",0,Set.of("http://localhost:5173"),1,1000);
        var network = new NetworkConfig("127.0.0.1",0,2,2000,5000,1000);
        var manager = new DefaultConnectionManager(2); var dispatcher = new NetworkRequestDispatcher();
        var gateway = new WebSocketGateway(config,network,manager,dispatcher);
        var writing = new CountDownLatch(1); var disconnected = new CountDownLatch(1);
        Session socket = (Session)Proxy.newProxyInstance(Session.class.getClassLoader(),new Class<?>[]{Session.class},(proxy,method,args)-> {
            if(method.getName().equals("sendText")) writing.countDown(); // Deliberately never complete send callback.
            if(method.getName().equals("disconnect")) disconnected.countDown();
            if(method.getReturnType()==boolean.class)return true;
            if(method.getReturnType()==int.class)return 0;
            if(method.getReturnType()==long.class)return 0L;
            return null;
        });
        try {
            var connection = new WebSocketConnection(gateway,dispatcher,manager,network,config);
            connection.onWebSocketOpen(socket);
            var message = new Message("1.0","r","",Command.ACK,ErrorCode.NONE,Map.of());
            connection.send(message); assertTrue(writing.await(2,TimeUnit.SECONDS));
            connection.send(message); assertThrows(java.io.IOException.class,()->connection.send(message));
            assertTrue(disconnected.await(2,TimeUnit.SECONDS));
        } finally { gateway.close(); manager.close(); }
        assertTrue(gateway.isTerminated()); assertEquals(0,manager.size());
    }
    @Test void codecRejectsSpoofingDuplicateKeysNestedValuesAndExtraFrames() {
        var codec=new WebJsonCodec();
        for(String text:List.of("{\"requestId\":\"r\",\"command\":\"PING\",\"payload\":{},\"sessionId\":\"other\"}",
            "{\"requestId\":\"r\",\"requestId\":\"s\",\"command\":\"PING\",\"payload\":{}}",
            "{\"requestId\":\"r\",\"command\":\"PING\",\"payload\":{\"x\":{}}}",
            "{\"requestId\":\"r\",\"command\":\"PING\",\"payload\":{}} {}"))
            assertThrows(java.io.IOException.class,()->codec.request(text,""));
    }
}
