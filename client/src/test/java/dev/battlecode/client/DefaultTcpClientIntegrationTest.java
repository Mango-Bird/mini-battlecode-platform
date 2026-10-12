package dev.battlecode.client;

import dev.battlecode.protocol.*;
import org.junit.jupiter.api.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(8)
class DefaultTcpClientIntegrationTest {
    private final MessageCodec codec = new LengthPrefixedMessageCodec();
    private final ExecutorService peer = Executors.newVirtualThreadPerTaskExecutor();
    private ServerSocket listener;
    private volatile Socket accepted;
    private DefaultTcpClient client;

    @BeforeEach void start() throws IOException {
        listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress()); listener.setSoTimeout(2000);
        client = new DefaultTcpClient(1000, 500);
    }
    @AfterEach void stop() throws Exception {
        client.close(); listener.close(); if (accepted != null) accepted.close();
        peer.shutdownNow(); assertTrue(peer.awaitTermination(2, TimeUnit.SECONDS)); assertTrue(client.awaitTermination(2, TimeUnit.SECONDS));
    }
    @FunctionalInterface interface Script { void run(Socket socket) throws Exception; }
    private Future<Void> script(Script script) {
        return peer.submit(() -> {
            try (var socket = listener.accept()) {
                accepted = socket; socket.setSoTimeout(2000); script.run(socket); return null;
            }
        });
    }
    private Message read(Socket socket) throws IOException { return codec.read(socket.getInputStream()).orElseThrow(); }
    private void ack(Socket socket, Message request, String session) throws IOException {
        codec.write(socket.getOutputStream(), new Message("1.0", request.requestId(), session, Command.ACK, ErrorCode.NONE, Map.of("for", request.command().name())));
    }
    private void connect() throws IOException { client.connect(listener.getInetAddress().getHostAddress(), listener.getLocalPort()); }

    @Test void demultiplexesEventBetweenHelloAndPingResponse() throws Exception {
        String session = UUID.randomUUID().toString();
        var task = script(socket -> {
            ack(socket, read(socket), session);
            Message ping = read(socket);
            codec.write(socket.getOutputStream(), new Message("1.0", "", session, Command.ROOM_EVENT, ErrorCode.NONE, Map.of("type", "CLOSED", "roomId", UUID.randomUUID().toString(), "status", "CLOSED", "players", "0")));
            ack(socket, ping, session);
            ack(socket, read(socket), session);
        });
        connect(); assertEquals(Command.ACK, client.hello("A").command());
        assertEquals(Command.ACK, client.request(Command.PING, Map.of()).command());
        assertEquals(Command.ROOM_EVENT, client.pollEvent().orElseThrow().command());
        assertEquals(session, client.sessionId()); assertEquals(Command.ACK, client.request(Command.QUIT, Map.of()).command());
        task.get(2, TimeUnit.SECONDS); assertTrue(client.isClosed());
    }

    @Test void responseTimeoutClosesTransport() throws Exception {
        var task = script(socket -> { read(socket); assertEquals(-1, socket.getInputStream().read()); });
        connect(); assertThrows(IOException.class, () -> client.hello("A"));
        assertTrue(client.isClosed()); task.get(2, TimeUnit.SECONDS);
    }

    @Test void wrongRequestIdIsRejectedWithoutHanging() throws Exception {
        var task = script(socket -> {
            read(socket);
            codec.write(socket.getOutputStream(), new Message("1.0", "other", UUID.randomUUID().toString(), Command.ACK, ErrorCode.NONE, Map.of("for", "HELLO")));
            assertEquals(-1, socket.getInputStream().read());
        });
        connect(); assertThrows(IOException.class, () -> client.hello("A")); task.get(2, TimeUnit.SECONDS);
    }

    @Test void serverErrorIsReturnedForDisplay() throws Exception {
        var task = script(socket -> {
            Message hello = read(socket);
            codec.write(socket.getOutputStream(), new Message("1.0", hello.requestId(), "", Command.ERROR, ErrorCode.INVALID_PAYLOAD, Map.of("message", "Invalid name")));
            assertEquals(-1, socket.getInputStream().read());
        });
        connect(); assertEquals(ErrorCode.INVALID_PAYLOAD, client.hello(" ").errorCode());
        client.close(); task.get(2, TimeUnit.SECONDS);
    }
}
