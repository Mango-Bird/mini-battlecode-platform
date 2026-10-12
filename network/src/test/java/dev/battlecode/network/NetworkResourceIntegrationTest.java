package dev.battlecode.network;

import dev.battlecode.protocol.*;
import org.junit.jupiter.api.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class NetworkResourceIntegrationTest {
    @Test void outboundQueueOverflowClosesAndCleansExactlyOnce() throws Exception {
        var disconnects = new AtomicInteger();
        RequestDispatcher dispatcher = new RequestDispatcher() {
            public Message dispatch(UUID id, Message request) { throw new UnsupportedOperationException(); }
            public void disconnected(UUID id) { disconnects.incrementAndGet(); }
        };
        try (var listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             var peer = new Socket(listener.getInetAddress(), listener.getLocalPort());
             var transport = listener.accept()) {
            var manager = new DefaultConnectionManager(1);
            var handler = new SocketConnectionHandler(transport, NetworkConfig.defaults(), new LengthPrefixedMessageCodec(), dispatcher, manager, System.nanoTime());
            manager.register(handler);
            var message = new Message("1.0", "r", "", Command.ACK, ErrorCode.NONE, Map.of("for", "PING"));
            // No writer is started: the queue cannot drain, so capacity is deterministic.
            for (int i = 0; i < SocketConnectionHandler.OUTBOUND_CAPACITY; i++) handler.send(message);
            assertThrows(IOException.class, () -> handler.send(message));
            handler.close(); manager.close();
            assertEquals(ConnectionState.CLOSED, handler.state()); assertEquals(0, manager.size()); assertEquals(1, disconnects.get());
        }
    }

    @Test void slowReaderIsDisconnectedByWriteDeadline() throws Exception {
        var disconnects = new AtomicInteger();
        String session = UUID.randomUUID().toString();
        RequestDispatcher dispatcher = new RequestDispatcher() {
            public Message dispatch(UUID id, Message request) {
                return new Message("1.0", request.requestId(), session, Command.ACK, ErrorCode.NONE,
                    request.command() == Command.HELLO ? Map.of("for", "HELLO") : Map.of("for", "PING", "padding", "x".repeat(60_000)));
            }
            public void disconnected(UUID id) { disconnects.incrementAndGet(); }
        };
        var codec = new LengthPrefixedMessageCodec();
        try (var server = new DefaultTcpServer(new NetworkConfig("127.0.0.1", 0, 1, 3000, 60_000, 10_000), dispatcher);
             var peer = new Socket()) {
            server.start(); peer.setReceiveBufferSize(1024);
            peer.connect(new InetSocketAddress("127.0.0.1", server.localPort()), 2000); peer.setSoTimeout(2000);
            codec.write(peer.getOutputStream(), new Message("1.0", "h", "", Command.HELLO, ErrorCode.NONE, Map.of("name", "Slow")));
            assertEquals(Command.ACK, codec.read(peer.getInputStream()).orElseThrow().command());
            // Flood tiny requests, then never read the large replies. The writer eventually blocks.
            for (int i = 0; i < 100; i++) codec.write(peer.getOutputStream(), new Message("1.0", "p" + i, session, Command.PING, ErrorCode.NONE, Map.of()));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
            while (server.connectionCount() != 0 && System.nanoTime() < deadline) Thread.sleep(20);
            assertEquals(0, server.connectionCount(), "Slow reader was not disconnected"); assertEquals(1, disconnects.get());
            server.close(); assertTrue(server.isTerminated());
        }
    }
}
