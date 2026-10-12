package dev.battlecode.server;

import dev.battlecode.client.*;
import dev.battlecode.network.NetworkConfig;
import dev.battlecode.rulesapi.InMemoryRuleRegistry;
import dev.battlecode.rulesimpl.*;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(25)
class Phase6BotIntegrationTest {
    private RoomTcpServer server;

    @BeforeEach void start() throws Exception {
        server = new RoomTcpServer(new NetworkConfig("127.0.0.1", 0, 16, 2000, 5000, 1000),
                new InMemoryRuleRegistry(List.of(new RoomOnlyRule(), new RockPaperScissorsRule(), new NumberGuessingRule(bound -> 0))));
        server.start();
    }

    @AfterEach void stop() throws Exception { server.close(); assertTrue(server.isTerminated()); }

    @Test void twoTcpBotsPlayNumberGuessingAndReleaseAllResources() throws Exception {
        try (var first = new BotClient(); var second = new BotClient(); var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            first.connect("127.0.0.1", server.localPort(), "bot-a");
            second.connect("127.0.0.1", server.localPort(), "bot-b");
            String room = first.createRoom("number-guessing", 24, 3000, Map.of("minNumber", "1", "maxNumber", "16"));
            second.joinRoom(room, "number-guessing");
            String match = first.startMatch();
            var a = workers.submit(() -> first.playUntilTerminal(BotStrategies.numberGuessing(), Duration.ofSeconds(8)));
            var b = workers.submit(() -> second.playUntilTerminal(BotStrategies.numberGuessing(), Duration.ofSeconds(8)));
            var resultA = a.get(10, TimeUnit.SECONDS); var resultB = b.get(10, TimeUnit.SECONDS);
            assertEquals(match, resultA.matchId()); assertEquals(match, resultB.matchId());
            assertEquals("FINISHED", resultA.status()); assertEquals(resultA.winners(), resultB.winners());
            assertTrue(resultA.reason().contains("secret=1"));
        }
        awaitServerCleanup();
    }

    @Test void realTcpBenchmarkSmokeUsesTwoBotConnectionsAndOneRoom() throws Exception {
        var report = BenchmarkRunner.run("127.0.0.1", server.localPort(), 2, Duration.ofSeconds(15));
        assertEquals(2, report.participants()); assertEquals(1, report.rooms());
        assertEquals(2, report.handshakes()); assertEquals(0, report.handshakeFailures());
        assertEquals(1, report.matches()); assertEquals(1, report.completedMatches());
        assertEquals(0, report.errors()); assertEquals(0, report.timeouts()); assertTrue(report.successfulRequests() >= 6);
        assertTrue(report.handshakeP95Millis() >= 0); assertTrue(report.matchP95Millis() >= 0);
        awaitServerCleanup();
    }

    @Test void botsReceiveCancellationOnTimeoutAndStillCleanUp() throws Exception {
        try (var first = new BotClient(); var second = new BotClient(); var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            first.connect("127.0.0.1", server.localPort(), "timeout-a");
            second.connect("127.0.0.1", server.localPort(), "timeout-b");
            String room = first.createRoom("number-guessing", 1, 100, Map.of("minNumber", "1", "maxNumber", "16"));
            second.joinRoom(room, "number-guessing"); first.startMatch();
            var a = workers.submit(() -> first.playUntilTerminal(BotStrategies.numberGuessing(), Duration.ofSeconds(5)));
            var b = workers.submit(() -> second.playUntilTerminal(BotStrategies.numberGuessing(), Duration.ofSeconds(5)));
            assertEquals("CANCELLED", a.get(7, TimeUnit.SECONDS).status());
            assertEquals("CANCELLED", b.get(7, TimeUnit.SECONDS).status());
        }
        awaitServerCleanup();
    }

    @Test void botDisconnectForfeitsOverTcpAndPeerGetsTerminalEvent() throws Exception {
        try (var first = new BotClient(); var second = new BotClient()) {
            first.connect("127.0.0.1", server.localPort(), "disconnect-a");
            second.connect("127.0.0.1", server.localPort(), "disconnect-b");
            String room = first.createRoom("rock-paper-scissors", 10, 3000, Map.of("roundsToWin", "1"));
            second.joinRoom(room, "rock-paper-scissors"); first.startMatch();
            second.close();
            var result = first.playUntilTerminal(BotStrategies.rps(3), Duration.ofSeconds(5));
            assertEquals("FINISHED", result.status());
            assertEquals(first.sessionId(), result.winners());
            assertTrue(second.awaitTermination(Duration.ofSeconds(2)));
        }
        awaitServerCleanup();
    }

    private void awaitServerCleanup() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while ((server.sessionCount() != 0 || server.connectionCount() != 0) && System.nanoTime() < deadline)
            Thread.sleep(10);
        assertEquals(0, server.sessionCount()); assertEquals(0, server.connectionCount());
    }
}
