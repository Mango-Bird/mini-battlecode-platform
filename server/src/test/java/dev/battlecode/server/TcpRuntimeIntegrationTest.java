package dev.battlecode.server;

import dev.battlecode.client.DefaultTcpClient;
import dev.battlecode.client.CliClient;
import dev.battlecode.network.*;
import dev.battlecode.protocol.*;
import org.junit.jupiter.api.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class TcpRuntimeIntegrationTest {
    private final MessageCodec codec = new LengthPrefixedMessageCodec();
    private DefaultTcpServer server;
    private NetworkRequestDispatcher dispatcher;

    @BeforeEach void start() throws IOException { restart(new NetworkConfig("127.0.0.1", 0, 32, 3000, 5000, 1000)); }

    private void restart(NetworkConfig config) throws IOException {
        if (server != null) server.close();
        dispatcher = new NetworkRequestDispatcher();
        server = new DefaultTcpServer(config, dispatcher); server.start();
    }

    @AfterEach void stop() throws IOException {
        server.close(); assertTrue(server.isTerminated()); assertEquals(0, server.connectionCount()); assertEquals(0, dispatcher.sessionCount());
    }

    private Socket socket() throws IOException {
        var socket = new Socket();
        try { socket.connect(new InetSocketAddress("127.0.0.1", server.localPort()), 2000); socket.setSoTimeout(3000); return socket; }
        catch (IOException ex) { socket.close(); throw ex; }
    }
    private Message hello(String id) { return new Message("1.0", id, "", Command.HELLO, ErrorCode.NONE, Map.of("name", "Tester")); }
    private Message request(String id, String session, Command command) { return new Message("1.0", id, session, command, ErrorCode.NONE, Map.of()); }
    private Message exchange(Socket socket, Message request) throws IOException {
        codec.write(socket.getOutputStream(), request); return read(socket);
    }
    private Message read(Socket socket) throws IOException { return codec.read(socket.getInputStream()).orElseThrow(() -> new EOFException("Missing response")); }
    private void closed(Socket socket) throws IOException {
        try { assertEquals(-1, socket.getInputStream().read()); }
        catch (SocketTimeoutException ex) { fail("Server did not close connection", ex); }
        catch (SocketException expected) { /* Reset is also a closed transport. */ }
    }
    private static void eventually(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "Condition did not become true within 3 seconds");
    }

    @Test void validHelloAndPingKeepConnectionOpen() throws Exception {
        try (var socket = socket()) {
            var ack = exchange(socket, hello("hello1"));
            assertEquals(Command.ACK, ack.command()); assertEquals("HELLO", ack.payload().get("for"));
            assertEquals(ack.sessionId(), UUID.fromString(ack.sessionId()).toString());
            var ping = exchange(socket, request("ping1", ack.sessionId(), Command.PING));
            assertEquals("ping1", ping.requestId()); assertEquals("PING", ping.payload().get("for"));
            assertEquals(ack.sessionId(), ping.sessionId()); assertEquals(1, server.connectionCount());
        }
    }

    @Test void twentyConcurrentClientsHaveDistinctSessions() throws Exception {
        var clients = new CopyOnWriteArrayList<DefaultTcpClient>();
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var futures = new ArrayList<Future<String>>();
            for (int i = 0; i < 20; i++) futures.add(executor.submit(() -> {
                var client = new DefaultTcpClient(); clients.add(client);
                client.connect("127.0.0.1", server.localPort()); assertEquals(Command.ACK, client.hello("Bot").command());
                assertEquals(Command.ACK, client.request(Command.PING, Map.of()).command()); return client.sessionId();
            }));
            var sessions = new HashSet<String>();
            for (var future : futures) assertTrue(sessions.add(future.get(5, TimeUnit.SECONDS)));
            assertEquals(20, server.connectionCount());
        } finally {
            executor.shutdownNow();
            for (var client : clients) client.close();
            boolean terminated = executor.awaitTermination(2, TimeUnit.SECONDS);
            for (var client : clients) {
                client.close(); terminated &= client.awaitTermination(2, TimeUnit.SECONDS);
            }
            assertTrue(terminated, "Client tasks did not terminate");
        }
    }

    @Test void incompatibleVersionGetsErrorThenClose() throws Exception {
        try (var socket = socket()) {
            var response = exchange(socket, new Message("2.0", "r", "", Command.HELLO, ErrorCode.NONE, Map.of("name", "A")));
            assertEquals(ErrorCode.UNSUPPORTED_VERSION, response.errorCode()); assertEquals("r", response.requestId()); closed(socket);
        }
    }

    @Test void helloFrameFragmentedAcrossWrites() throws Exception {
        try (var socket = socket()) {
            var encoded = new ByteArrayOutputStream(); codec.write(encoded, hello("split"));
            byte[] bytes = encoded.toByteArray();
            for (int i = 0; i < bytes.length; i += 3) {
                socket.getOutputStream().write(bytes, i, Math.min(3, bytes.length - i)); socket.getOutputStream().flush();
                Thread.sleep(2);
            }
            assertEquals("split", read(socket).requestId());
        }
    }

    @Test void consecutiveFramesAreReadIndependently() throws Exception {
        try (var socket = socket()) {
            String session = exchange(socket, hello("h")).sessionId();
            var frames = new ByteArrayOutputStream();
            codec.write(frames, request("p1", session, Command.PING)); codec.write(frames, request("p2", session, Command.PING));
            socket.getOutputStream().write(frames.toByteArray()); socket.getOutputStream().flush();
            assertEquals("p1", read(socket).requestId()); assertEquals("p2", read(socket).requestId());
        }
    }

    @Test void oversizedFrameClosesBeforeBodyIsSent() throws Exception {
        try (var socket = socket()) {
            new DataOutputStream(socket.getOutputStream()).writeInt(65_537); socket.getOutputStream().flush(); closed(socket);
        }
    }

    @Test void malformedBodyClosesConnection() throws Exception {
        try (var socket = socket()) {
            socket.getOutputStream().write(new byte[]{0,0,0,1,0}); socket.getOutputStream().flush(); closed(socket);
        }
    }

    @Test void truncatedHeaderOrBodyClosesWithoutResponse() throws Exception {
        for (byte[] partial : List.of(new byte[]{0,0}, new byte[]{0,0,0,10,1,2})) {
            try (var socket = socket()) {
                socket.getOutputStream().write(partial); socket.getOutputStream().flush(); socket.shutdownOutput(); closed(socket);
            }
        }
    }

    @Test void serverOnlyCommandIsRejectedWithoutEndingSession() throws Exception {
        try (var socket = socket()) {
            String session = exchange(socket, hello("h")).sessionId();
            assertEquals(ErrorCode.INVALID_STATE, exchange(socket, request("s", session, Command.ACK)).errorCode());
            assertEquals(Command.ACK, exchange(socket, request("p", session, Command.PING)).command());
        }
    }

    @Test void abruptDisconnectCleansSessionAndConnection() throws Exception {
        var socket = socket();
        try {
            exchange(socket, hello("h")); assertEquals(1, dispatcher.sessionCount()); socket.setSoLinger(true, 0);
        } finally { socket.close(); }
        eventually(() -> server.connectionCount() == 0 && dispatcher.sessionCount() == 0);
        try (var replacement = socket()) { assertEquals(Command.ACK, exchange(replacement, hello("new")).command()); }
    }

    @Test void handshakeTimeoutClosesSilentClient() throws Exception {
        restart(new NetworkConfig("127.0.0.1", 0, 32, 150, 5000, 1000));
        try (var socket = socket()) { closed(socket); }
        eventually(() -> server.connectionCount() == 0);
    }

    @Test void frameDeadlineDoesNotResetWhenBytesArrive() throws Exception {
        restart(new NetworkConfig("127.0.0.1", 0, 32, 3000, 5000, 150));
        try (var socket = socket()) {
            socket.getOutputStream().write(0); socket.getOutputStream().flush(); Thread.sleep(90);
            socket.getOutputStream().write(0); socket.getOutputStream().flush(); Thread.sleep(100);
            closed(socket);
        }
    }

    @Test void idleTimeoutAfterHello() throws Exception {
        restart(new NetworkConfig("127.0.0.1", 0, 32, 1000, 150, 1000));
        try (var socket = socket()) { exchange(socket, hello("h")); closed(socket); }
    }

    @Test void businessCommandBeforeHelloIsRejectedAndClosed() throws Exception {
        try (var socket = socket()) {
            assertEquals(ErrorCode.HANDSHAKE_REQUIRED, exchange(socket, request("p", "", Command.LIST_ROOMS)).errorCode()); closed(socket);
        }
    }

    @Test void invalidHelloPayloadCanBeCorrectedBeforeDeadline() throws Exception {
        try (var socket = socket()) {
            assertEquals(ErrorCode.INVALID_PAYLOAD, exchange(socket, new Message("1.0", "bad", "", Command.HELLO, ErrorCode.NONE, Map.of("name", " "))).errorCode());
            assertEquals(Command.ACK, exchange(socket, hello("good")).command());
        }
    }

    @Test void repeatedHelloGetsInvalidStateWithoutChangingSession() throws Exception {
        try (var socket = socket()) {
            String session = exchange(socket, hello("h")).sessionId();
            var second = new Message("1.0", "h2", session, Command.HELLO, ErrorCode.NONE, Map.of("name", "B"));
            assertEquals(ErrorCode.INVALID_STATE, exchange(socket, second).errorCode());
            assertEquals(Command.ACK, exchange(socket, request("p", session, Command.PING)).command());
        }
    }

    @Test void spoofedSessionIsRejectedAndClosed() throws Exception {
        try (var socket = socket()) {
            exchange(socket, hello("h"));
            assertEquals(ErrorCode.INVALID_SESSION, exchange(socket, request("p", UUID.randomUUID().toString(), Command.PING)).errorCode()); closed(socket);
        }
    }

    @Test void duplicateRequestIdIsNotExecutedTwice() throws Exception {
        try (var socket = socket()) {
            String session = exchange(socket, hello("h")).sessionId();
            assertEquals(Command.ACK, exchange(socket, request("p", session, Command.PING)).command());
            assertEquals(ErrorCode.INVALID_PAYLOAD, exchange(socket, request("p", session, Command.PING)).errorCode());
        }
    }

    @Test void quitAckIsFlushedBeforeDisconnect() throws Exception {
        try (var socket = socket()) {
            String session = exchange(socket, hello("h")).sessionId();
            assertEquals("QUIT", exchange(socket, request("q", session, Command.QUIT)).payload().get("for")); closed(socket);
        }
    }

    @Test void admissionLimitRejectsExtraSocketWithoutBreakingExistingClient() throws Exception {
        restart(new NetworkConfig("127.0.0.1", 0, 1, 3000, 5000, 1000));
        try (var first = socket()) {
            String session = exchange(first, hello("h")).sessionId();
            try (var second = socket()) { closed(second); }
            assertEquals(Command.ACK, exchange(first, request("p", session, Command.PING)).command());
        }
    }

    @Test void noLobbyOrRulesAreCreated() throws Exception {
        try (var socket = socket()) {
            String session = exchange(socket, hello("h")).sessionId();
            var rules = exchange(socket, request("r", session, Command.LIST_RULES));
            var rooms = exchange(socket, request("s", session, Command.LIST_ROOMS));
            assertEquals("0", rules.payload().get("count")); assertEquals("-1", rooms.payload().get("nextOffset"));
            assertEquals(ErrorCode.INVALID_STATE, exchange(socket, request("l", session, Command.LEAVE_ROOM)).errorCode());
        }
    }

    @Test void gracefulShutdownClosesActiveAndUnhandshakenSocketsAndTasks() throws Exception {
        try (var active = socket(); var waiting = socket()) {
            exchange(active, hello("h")); eventually(() -> server.connectionCount() == 2);
            int port = server.localPort(); server.close();
            assertTrue(server.isTerminated()); closed(active); closed(waiting); assertEquals(0, dispatcher.sessionCount());
            try (var attempt = new Socket()) {
                assertThrows(IOException.class, () -> attempt.connect(new InetSocketAddress("127.0.0.1", port), 1000));
            }
            server.close(); // Idempotent shutdown.
        }
    }

    @Test void cliProcessRemainsInteractiveAndExitsOnExit() throws Exception {
        runCli(false);
    }

    @Test void cliPromptsForIpPortAndNameWhenArgumentsAbsent() throws Exception {
        runCli(true);
    }

    private void runCli(boolean prompt) throws Exception {
        String javaExecutable = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        if (!java.nio.file.Files.exists(java.nio.file.Path.of(javaExecutable)))
            javaExecutable = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = java.nio.file.Path.of(CliClient.class.getProtectionDomain().getCodeSource().getLocation().toURI())
            + File.pathSeparator + java.nio.file.Path.of(Message.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var command = new ArrayList<>(List.of(javaExecutable, "-cp", classpath, CliClient.class.getName()));
        if (!prompt) command.addAll(List.of("127.0.0.1", Integer.toString(server.localPort()), "CLI-Test"));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            try (var input = new OutputStreamWriter(process.getOutputStream(), java.nio.charset.StandardCharsets.UTF_8)) {
                if (prompt) input.write("127.0.0.1\n" + server.localPort() + "\nCLI-Test\n");
                input.write("ping\nlist_rules\nlist_rooms\nexit\n"); input.flush();
            }
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "CLI did not exit");
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("Session ID:"), output);
            for (String cmd : List.of("HELLO", "PING", "LIST_RULES", "LIST_ROOMS", "QUIT"))
                assertTrue(output.contains("for=" + cmd), output);
            if (prompt) assertTrue(output.contains("Server IP"), output);
        } finally {
            process.destroyForcibly(); assertTrue(process.waitFor(2, TimeUnit.SECONDS));
        }
        eventually(() -> server.connectionCount() == 0);
    }
}
