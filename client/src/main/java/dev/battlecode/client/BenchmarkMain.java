package dev.battlecode.client;

import java.time.Duration;

/** Bounded opt-in TCP benchmark: profile sizes are explicit, never stress-tested by default. */
public final class BenchmarkMain {
    private BenchmarkMain() { }
    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 4) {
            System.err.println("Usage: BenchmarkMain <host> <port> [even-participants=2] [overall-seconds=30]");
            System.exit(2);
        }
        int participants = args.length > 2 ? Integer.parseInt(args[2]) : 2;
        int seconds = args.length > 3 ? Integer.parseInt(args[3]) : 30;
        var report = BenchmarkRunner.run(args[0], Integer.parseInt(args[1]), participants, Duration.ofSeconds(seconds));
        System.out.printf("TCP benchmark host=%s port=%d participants=%d rooms=%d elapsedMs=%d handshakes=%d handshakeFailures=%d requests=%d requestFailures=%d matches=%d completed=%d cancelled=%d errors=%d timeouts=%d handshakeP50/P95ms=%d/%d matchP50/P95ms=%d/%d completionsPerSecond=%.3f%n",
                report.host(), report.port(), report.participants(), report.rooms(), report.elapsedNanos() / 1_000_000,
                report.handshakes(), report.handshakeFailures(), report.successfulRequests(), report.failedRequests(),
                report.matches(), report.completedMatches(), report.cancelledMatches(), report.errors(), report.timeouts(), report.handshakeP50Millis(),
                report.handshakeP95Millis(), report.matchP50Millis(), report.matchP95Millis(), report.completedMatchesPerSecond());
    }
}
