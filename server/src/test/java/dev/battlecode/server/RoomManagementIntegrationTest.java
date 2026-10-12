package dev.battlecode.server;

import dev.battlecode.client.*;
import dev.battlecode.competition.*;
import dev.battlecode.network.NetworkConfig;
import dev.battlecode.protocol.*;
import dev.battlecode.rulesimpl.NumberGuessingRule;
import dev.battlecode.rulesimpl.RockPaperScissorsRule;
import dev.battlecode.rulesimpl.RoomOnlyRule;
import org.junit.jupiter.api.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class RoomManagementIntegrationTest {
    private RoomTcpServer server;
    private final List<DefaultTcpClient> clients = new CopyOnWriteArrayList<>();
    private final MessageCodec codec = new LengthPrefixedMessageCodec();

    @BeforeEach void start() throws IOException {
        server = new RoomTcpServer(new NetworkConfig("127.0.0.1", 0, 64, 3000, 5000, 1000)); server.start();
    }
    @AfterEach void stop() throws Exception {
        for (var client : clients) client.close();
        server.close();
        for (var client : clients) assertTrue(client.awaitTermination(2, TimeUnit.SECONDS));
        assertTrue(server.isTerminated()); assertEquals(0, server.sessionCount()); assertTrue(server.lobby().rooms().isEmpty());
    }
    private DefaultTcpClient client() throws IOException {
        var client = new DefaultTcpClient(); clients.add(client);
        client.connect("127.0.0.1", server.localPort()); assertEquals(Command.ACK, client.hello("Player").command()); return client;
    }
    private Message create(DefaultTcpClient client, int capacity) throws IOException {
        return client.request(Command.CREATE_ROOM, Map.of("ruleId", RoomOnlyRule.ID, "maxPlayers", Integer.toString(capacity), "maxTurns", "100", "turnTimeoutMillis", "1000"));
    }
    private String room(DefaultTcpClient client, int capacity) throws IOException {
        var response = create(client, capacity); assertEquals(Command.ACK, response.command()); return response.payload().get("roomId");
    }
    private Message join(DefaultTcpClient client, String room) throws IOException { return client.request(Command.JOIN_ROOM, Map.of("roomId", room)); }
    private Message leave(DefaultTcpClient client) throws IOException { return client.request(Command.LEAVE_ROOM, Map.of()); }
    private Room snapshot(String id) { return server.lobby().rooms().stream().filter(room -> room.roomId().toString().equals(id)).findFirst().orElseThrow(); }

    private Message event(DefaultTcpClient client, String type) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < end) {
            var event = client.pollEvent(Math.max(1, end - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (event.isPresent() && type.equals(event.get().payload().get("type"))) return event.get();
        }
        throw new AssertionError("Missing room event " + type);
    }
    private void eventually(BooleanSupplier condition) throws Exception {
        var scheduler = Executors.newSingleThreadScheduledExecutor(); var done = new CompletableFuture<Void>();
        try {
            scheduler.scheduleAtFixedRate(() -> {
                try { if (condition.getAsBoolean()) done.complete(null); }
                catch (Throwable ex) { done.completeExceptionally(ex); }
            }, 0, 10, TimeUnit.MILLISECONDS);
            done.get(3, TimeUnit.SECONDS);
        } finally { scheduler.shutdownNow(); assertTrue(scheduler.awaitTermination(2, TimeUnit.SECONDS)); }
    }
    private List<Message> race(Callable<Message> first, Callable<Message> second) throws Exception {
        var executor = Executors.newVirtualThreadPerTaskExecutor(); var barrier = new CyclicBarrier(3);
        try {
            var a = executor.submit(() -> { barrier.await(2, TimeUnit.SECONDS); return first.call(); });
            var b = executor.submit(() -> { barrier.await(2, TimeUnit.SECONDS); return second.call(); });
            barrier.await(2, TimeUnit.SECONDS); return List.of(a.get(4, TimeUnit.SECONDS), b.get(4, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS)); }
    }

    @Test void createsRoomAndRegistersCreatorAsHost() throws Exception {
        var host = client(); String id = room(host, 3); Room snapshot = snapshot(id);
        assertEquals(UUID.fromString(host.sessionId()), snapshot.ownerId()); assertEquals(Set.of(snapshot.ownerId()), snapshot.players());
        assertEquals(RoomOnlyRule.ID, snapshot.ruleId()); assertEquals(3, snapshot.config().maxPlayers());
    }
    @Test void roomListReflectsStateWithoutExposingSessionIds() throws Exception {
        var host = client(); String id = room(host, 2); var observer = client();
        var list = observer.request(Command.LIST_ROOMS, Map.of());
        assertEquals("1", list.payload().get("count")); assertEquals(id, list.payload().get("room.0.id"));
        assertEquals("WAITING", list.payload().get("room.0.status")); assertEquals("1", list.payload().get("room.0.players"));
        assertEquals("2", list.payload().get("room.0.maxPlayers")); assertFalse(list.payload().containsValue(host.sessionId()));
        assertEquals(Set.of("for", "count", "nextOffset", "room.0.id", "room.0.ruleId", "room.0.status", "room.0.players", "room.0.maxPlayers"), list.payload().keySet());
    }
    @Test void twoClientsJoinAndBothReceiveRoomEvent() throws Exception {
        var host = client(); var guest = client(); String id = room(host, 2);
        Message created = event(host, "CREATED"); assertEquals("", created.requestId()); assertEquals(host.sessionId(), created.sessionId());
        assertEquals(Command.ACK, join(guest, id).command());
        for (var client : List.of(host, guest)) {
            Message joined = event(client, "JOINED"); assertEquals("2", joined.payload().get("players")); assertEquals(id, joined.payload().get("roomId"));
            assertEquals(client.sessionId(), joined.sessionId());
        }
        assertEquals(2, snapshot(id).players().size());
    }
    @Test void fullRoomRejectsJoinWithoutMutatingMembership() throws Exception {
        var host = client(); var a = client(); var b = client(); String id = room(host, 2); join(a, id);
        assertEquals(ErrorCode.ROOM_FULL, join(b, id).errorCode()); assertEquals(2, snapshot(id).players().size());
        assertEquals(Command.ACK, create(b, 2).command());
    }
    @Test void sessionCannotJoinSecondRoomOrRepeatJoin() throws Exception {
        var a = client(); var b = client(); var guest = client(); String first = room(a, 3), second = room(b, 3);
        assertEquals(Command.ACK, join(guest, first).command());
        assertEquals(ErrorCode.INVALID_STATE, join(guest, second).errorCode()); assertEquals(ErrorCode.INVALID_STATE, join(guest, first).errorCode());
        assertEquals(2, snapshot(first).players().size()); assertEquals(1, snapshot(second).players().size());
        assertEquals(ErrorCode.INVALID_STATE, create(guest, 2).errorCode());
    }
    @Test void hostTransfersToEarliestRemainingMember() throws Exception {
        var host = client(); var first = client(); var second = client(); String id = room(host, 3); join(first, id); join(second, id);
        assertEquals(Command.ACK, leave(host).command()); assertEquals(UUID.fromString(first.sessionId()), snapshot(id).ownerId());
        assertEquals("2", event(first, "LEFT").payload().get("players"));
        leave(first); assertEquals(UUID.fromString(second.sessionId()), snapshot(id).ownerId());
        leave(second); assertTrue(server.lobby().rooms().isEmpty());
    }
    @Test void hostDisconnectTransfersAndLeavesNoGhost() throws Exception {
        var host = client(); var guest = client(); String id = room(host, 2); join(guest, id); host.close();
        event(guest, "LEFT"); assertEquals(UUID.fromString(guest.sessionId()), snapshot(id).ownerId());
        assertEquals(Set.of(UUID.fromString(guest.sessionId())), snapshot(id).players()); assertEquals(1, server.sessionCount());
    }
    @Test void guestDisconnectCleansMembershipExactlyOnce() throws Exception {
        var host = client(); var guest = client(); String id = room(host, 2); join(guest, id); guest.close(); guest.close();
        assertEquals("1", event(host, "LEFT").payload().get("players")); assertEquals(Set.of(UUID.fromString(host.sessionId())), snapshot(id).players());
        assertEquals(1, server.sessionCount());
    }
    @Test void lastMemberLeavesAndRoomDisappears() throws Exception {
        var host = client(); room(host, 2); leave(host);
        Message closed = event(host, "CLOSED"); assertEquals("0", closed.payload().get("players")); assertEquals("CLOSED", closed.payload().get("status"));
        assertEquals("0", host.request(Command.LIST_ROOMS, Map.of()).payload().get("count"));
    }
    @Test void twentyClientsCreateRoomsConcurrently() throws Exception {
        var executor = Executors.newVirtualThreadPerTaskExecutor(); var gate = new CountDownLatch(1);
        try {
            var connected = new ArrayList<DefaultTcpClient>(); for (int i = 0; i < 20; i++) connected.add(client());
            var futures = new ArrayList<Future<String>>();
            for (var client : connected) futures.add(executor.submit(() -> { assertTrue(gate.await(2, TimeUnit.SECONDS)); return room(client, 2); }));
            gate.countDown(); var ids = new HashSet<String>();
            for (var future : futures) assertTrue(ids.add(future.get(4, TimeUnit.SECONDS)));
            assertEquals(20, server.lobby().rooms().size()); assertEquals(20, server.sessionCount());
            for (var room : server.lobby().rooms()) assertEquals(Set.of(room.ownerId()), room.players());
        } finally { gate.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS)); }
    }
    @Test void finalSlotRaceAcceptsExactlyOneClient() throws Exception {
        var host = client(); var a = client(); var b = client(); String id = room(host, 2);
        var responses = race(() -> join(a, id), () -> join(b, id));
        assertEquals(1, responses.stream().filter(m -> m.command() == Command.ACK).count());
        assertEquals(1, responses.stream().filter(m -> m.errorCode() == ErrorCode.ROOM_FULL).count()); assertEquals(2, snapshot(id).players().size());
    }
    @Test void joinRacingDisconnectNeverLeavesGhost() throws Exception {
        var host = client(); String id = room(host, 2);
        for (int i = 0; i < 10; i++) {
            var guest = client(); var executor = Executors.newVirtualThreadPerTaskExecutor(); var barrier = new CyclicBarrier(3);
            try {
                var joining = executor.submit(() -> { barrier.await(2, TimeUnit.SECONDS); try { return join(guest, id); } catch (IOException expected) { return null; } });
                var closing = executor.submit(() -> { barrier.await(2, TimeUnit.SECONDS); guest.close(); return true; });
                barrier.await(2, TimeUnit.SECONDS); joining.get(4, TimeUnit.SECONDS); assertTrue(closing.get(4, TimeUnit.SECONDS));
            } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS)); }
            eventually(() -> server.sessionCount() == 1 && snapshot(id).players().size() == 1);
            assertFalse(snapshot(id).players().contains(UUID.fromString(guest.sessionId())));
        }
    }
    @Test void hostLeaveRacingJoinPreservesOwnerInvariant() throws Exception {
        var host = client(); var guest = client(); String id = room(host, 2);
        var responses = race(() -> join(guest, id), () -> leave(host));
        assertEquals(Command.ACK, responses.get(1).command());
        if (responses.getFirst().command() == Command.ACK) {
            assertEquals(UUID.fromString(guest.sessionId()), snapshot(id).ownerId()); assertEquals(1, snapshot(id).players().size());
        } else { assertEquals(ErrorCode.ROOM_NOT_FOUND, responses.getFirst().errorCode()); assertTrue(server.lobby().rooms().isEmpty()); }
    }
    @Test void concurrentLeavesDeleteRoomWithoutDeadlock() throws Exception {
        var members = new ArrayList<DefaultTcpClient>(); for (int i = 0; i < 12; i++) members.add(client());
        String id = room(members.getFirst(), 12); for (int i = 1; i < members.size(); i++) join(members.get(i), id);
        var executor = Executors.newVirtualThreadPerTaskExecutor(); var gate = new CountDownLatch(1);
        try {
            var futures = new ArrayList<Future<Message>>();
            for (var member : members) futures.add(executor.submit(() -> { assertTrue(gate.await(2, TimeUnit.SECONDS)); return leave(member); }));
            gate.countDown(); for (var future : futures) assertEquals(Command.ACK, future.get(4, TimeUnit.SECONDS).command());
            assertTrue(server.lobby().rooms().isEmpty());
        } finally { gate.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS)); }
    }
    @Test void spoofedSessionIsRejectedAndCannotJoinRoom() throws Exception {
        var host = client(); var attacker = client(); String id = room(host, 2);
        var spoof = new Message("1.0", "spoof", host.sessionId(), Command.JOIN_ROOM, ErrorCode.NONE, Map.of("roomId", id));
        assertEquals(ErrorCode.INVALID_SESSION, attacker.request(spoof).errorCode());
        eventually(() -> server.sessionCount() == 1); assertEquals(Set.of(UUID.fromString(host.sessionId())), snapshot(id).players());
    }
    @Test void nonexistentRuleIsRejected() throws Exception {
        var client = client();
        assertEquals(ErrorCode.RULE_NOT_FOUND, client.request(Command.CREATE_ROOM, Map.of("ruleId", "unknown", "maxPlayers", "2", "maxTurns", "10", "turnTimeoutMillis", "1000")).errorCode());
        assertTrue(server.lobby().rooms().isEmpty());
    }
    @Test void realRuleRegistryAndConfigValidationAreUsed() throws Exception {
        var client = client(); var rules = client.request(Command.LIST_RULES, Map.of());
        assertEquals("3", rules.payload().get("count"));
        assertEquals(Set.of(RoomOnlyRule.ID,RockPaperScissorsRule.ID,NumberGuessingRule.ID),
                rules.payload().entrySet().stream().filter(entry->entry.getKey().matches("rule\\.\\d+\\.id"))
                        .map(Map.Entry::getValue).collect(java.util.stream.Collectors.toSet()));
        assertEquals(ErrorCode.INVALID_PAYLOAD, client.request(Command.CREATE_ROOM, Map.of("ruleId", RoomOnlyRule.ID, "maxPlayers", "2", "maxTurns", "10", "turnTimeoutMillis", "1000", "option.unknown", "bad")).errorCode());
        assertTrue(server.lobby().rooms().isEmpty());
    }
    @Test void roomPaginationReturnsEveryRealRoomWithBoundedSchema() throws Exception {
        var expected = new HashSet<String>(); for (int i = 0; i < 12; i++) expected.add(room(client(), 2));
        var observer = client(); var actual = new HashSet<String>(); int offset = 0;
        do {
            var response = observer.request(Command.LIST_ROOMS, Map.of("offset", Integer.toString(offset), "limit", "3"));
            int count = Integer.parseInt(response.payload().get("count")); assertTrue(count <= 3); assertTrue(response.payload().size() <= 128);
            for (int i = 0; i < count; i++) assertTrue(actual.add(response.payload().get("room." + i + ".id")));
            offset = Integer.parseInt(response.payload().get("nextOffset"));
        } while (offset != -1);
        assertEquals(expected, actual);
        assertEquals("0", observer.request(Command.LIST_ROOMS, Map.of("offset", "2147483647")).payload().get("count"));
    }
    @Test void shutdownCleansEverySessionAndRoom() throws Exception {
        var host = client(); var guest = client(); String id = room(host, 2); join(guest, id); room(client(), 2);
        server.close(); assertEquals(0, server.sessionCount()); assertTrue(server.lobby().rooms().isEmpty()); assertTrue(server.isTerminated());
    }
    @Test void startMatchAndActionRemainUnavailable() throws Exception {
        var host = client(); String id = room(host, 2);
        assertEquals(ErrorCode.INVALID_STATE, host.request(Command.START_MATCH, Map.of("roomId", id)).errorCode());
        assertEquals(ErrorCode.INVALID_STATE, host.request(Command.ACTION, Map.of("matchId", UUID.randomUUID().toString(), "turn", "0")).errorCode());
        assertEquals(RoomStatus.WAITING, snapshot(id).status());
    }
    @Test void malformedFrameDisconnectCleansOwnedRoom() throws Exception {
        var observer = client();
        try (var raw = new Socket()) {
            raw.connect(new InetSocketAddress("127.0.0.1", server.localPort()), 1000); raw.setSoTimeout(2000);
            var hello = new Message("1.0", "h", "", Command.HELLO, ErrorCode.NONE, Map.of("name", "Raw"));
            codec.write(raw.getOutputStream(), hello); Message ack = codec.read(raw.getInputStream()).orElseThrow();
            codec.write(raw.getOutputStream(), new Message("1.0", "c", ack.sessionId(), Command.CREATE_ROOM, ErrorCode.NONE,
                Map.of("ruleId", RoomOnlyRule.ID, "maxPlayers", "2", "maxTurns", "10", "turnTimeoutMillis", "1000")));
            Message created; do { created = codec.read(raw.getInputStream()).orElseThrow(); } while (!"c".equals(created.requestId()));
            assertEquals(Command.ACK, created.command());
            raw.getOutputStream().write(new byte[]{0,0,0,1,0}); raw.getOutputStream().flush();
            eventually(() -> server.sessionCount() == 1 && server.lobby().rooms().isEmpty());
        }
        assertEquals("0", observer.request(Command.LIST_ROOMS, Map.of()).payload().get("count"));
    }
    @Test void idleTimeoutCleansSessionAndRoom() throws Exception {
        server.close(); server = new RoomTcpServer(new NetworkConfig("127.0.0.1", 0, 64, 1000, 200, 1000)); server.start();
        room(client(), 2); eventually(() -> server.sessionCount() == 0 && server.lobby().rooms().isEmpty());
    }
    @Test void demoSequenceCreateListJoinLeaveAndDelete() throws Exception {
        var a = client(); var b = client(); String room01 = room(a, 2);
        assertEquals(room01, b.request(Command.LIST_ROOMS, Map.of()).payload().get("room.0.id"));
        assertEquals(Command.ACK, join(b, room01).command()); event(a, "JOINED"); event(b, "JOINED");
        leave(b); assertEquals("1", event(a, "LEFT").payload().get("players")); leave(a); event(a, "CLOSED");
        assertEquals("0", b.request(Command.LIST_ROOMS, Map.of()).payload().get("count"));
    }

    @Test void cliCanCreateRoomAndPrintEventsWhileWaitingForInput() throws Exception {
        String javaExecutable = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        if (!java.nio.file.Files.exists(java.nio.file.Path.of(javaExecutable))) javaExecutable = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = java.nio.file.Path.of(CliClient.class.getProtectionDomain().getCodeSource().getLocation().toURI())
            + File.pathSeparator + java.nio.file.Path.of(Message.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Process process = new ProcessBuilder(javaExecutable, "-cp", classpath, CliClient.class.getName(), "127.0.0.1", Integer.toString(server.localPort()), "CLI-A").redirectErrorStream(true).start();
        var output = new ArrayBlockingQueue<String>(1024); var transcript = new CopyOnWriteArrayList<String>();
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var reader = executor.submit(() -> {
            try (var stream = new BufferedReader(new InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line; while ((line = stream.readLine()) != null) { transcript.add(line); assertTrue(output.offer(line)); }
            }
            return true;
        });
        try (var keyboard = new OutputStreamWriter(process.getOutputStream(), java.nio.charset.StandardCharsets.UTF_8)) {
            keyboard.write("create_room " + RoomOnlyRule.ID + " 2 100 1000\n"); keyboard.flush();
            String response = cliLine(output, "for=CREATE_ROOM");
            var match = java.util.regex.Pattern.compile("roomId=([a-f0-9-]{36})").matcher(response); assertTrue(match.find(), response);
            String id = match.group(1); var guest = client(); assertEquals(Command.ACK, join(guest, id).command());
            assertTrue(cliLine(output, "type=JOINED").contains("ROOM_EVENT")); // No Enter/new command from A.
            leave(guest); assertTrue(cliLine(output, "type=LEFT").contains("ROOM_EVENT"));
            keyboard.write("leave_room\n"); keyboard.flush(); cliLine(output, "for=LEAVE_ROOM");
            keyboard.write("exit\n"); keyboard.flush();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS)); assertTrue(reader.get(2, TimeUnit.SECONDS));
            String all = String.join("\n", transcript); assertEquals(0, process.exitValue(), all);
            assertTrue(all.contains("for=QUIT"), all); assertTrue(server.lobby().rooms().isEmpty());
        } finally {
            process.destroyForcibly(); assertTrue(process.waitFor(2, TimeUnit.SECONDS));
            executor.shutdownNow(); assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }
    private String cliLine(BlockingQueue<String> output, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            String line = output.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (line != null && line.contains(expected)) return line;
        }
        throw new AssertionError("CLI did not print " + expected);
    }
}
