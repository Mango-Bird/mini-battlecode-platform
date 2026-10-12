package dev.battlecode.client;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

/** Bounded real-TCP functional benchmark. Each room uses exactly two bot connections. */
public final class BenchmarkRunner {
    private BenchmarkRunner() { }

    public static Report run(String host, int port, int participants, Duration deadline) throws Exception {
        if (participants < 2 || participants > 50 || participants % 2 != 0) throw new IllegalArgumentException("participants must be even and in 2..50");
        if (deadline.isNegative() || deadline.isZero() || deadline.compareTo(Duration.ofMinutes(5)) > 0) throw new IllegalArgumentException("deadline must be in 1ns..5min");
        int rooms = participants / 2;
        long started = System.nanoTime();
        LongAdder handshakes = new LongAdder(), handshakesFailed = new LongAdder(), requests = new LongAdder(), requestsFailed = new LongAdder();
        LongAdder matches = new LongAdder(), completed = new LongAdder(), cancelled = new LongAdder(), errors = new LongAdder(), timeouts = new LongAdder();
        ConcurrentLinkedQueue<Long> handshakeLatencies = new ConcurrentLinkedQueue<>(), matchLatencies = new ConcurrentLinkedQueue<>();
        ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<?>> tasks = new ArrayList<>();
        long absoluteDeadline = started + deadline.toNanos();
        for (int roomIndex = 0; roomIndex < rooms; roomIndex++) {
            int index = roomIndex;
            tasks.add(workers.submit(() -> {
                try (var first = new BotClient(); var second = new BotClient()) {
                    long before = System.nanoTime();
                    try { first.connect(host, port, "Bench-A-" + index); handshakes.increment(); handshakeLatencies.add(System.nanoTime() - before); }
                    catch (IOException ex) { handshakesFailed.increment(); throw ex; }
                    before = System.nanoTime();
                    try { second.connect(host, port, "Bench-B-" + index); handshakes.increment(); handshakeLatencies.add(System.nanoTime() - before); }
                    catch (IOException ex) { handshakesFailed.increment(); throw ex; }
                    String room = first.createRoom("number-guessing", 24, 10_000, Map.of("minNumber", "1", "maxNumber", "16"));
                    second.joinRoom(room, "number-guessing");
                    long matchStarted = System.nanoTime(); first.startMatch(); matches.increment();
                    long remaining = absoluteDeadline - System.nanoTime();
                    if (remaining <= 0) throw new TimeoutException("Benchmark overall deadline exceeded");
                    var a = workers.submit(() -> first.playUntilTerminal(BotStrategies.numberGuessing(), Duration.ofNanos(remaining)));
                    var b = workers.submit(() -> second.playUntilTerminal(BotStrategies.numberGuessing(), Duration.ofNanos(remaining)));
                    var outcomeA = a.get(remaining, TimeUnit.NANOSECONDS); var outcomeB = b.get(remaining, TimeUnit.NANOSECONDS);
                    if (!outcomeA.matchId().equals(outcomeB.matchId())) throw new IllegalStateException("Bots observed different matches");
                    requests.add(first.successfulRequests() + second.successfulRequests());
                    requestsFailed.add(first.failedRequests() + second.failedRequests());
                    if (outcomeA.status().equals("FINISHED")) completed.increment(); else cancelled.increment();
                    matchLatencies.add(System.nanoTime() - matchStarted);
                } catch (Throwable failure) {
                    errors.increment();
                    if (failure instanceof TimeoutException || failure instanceof java.net.SocketTimeoutException) timeouts.increment();
                    throw new CompletionException(failure);
                }
            }));
        }
        try {
            for (Future<?> task : tasks) task.get(Math.max(1, absoluteDeadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (Exception failure) {
            tasks.forEach(task -> task.cancel(true)); workers.shutdownNow();
            throw failure;
        } finally {
            workers.shutdown();
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) workers.shutdownNow();
        }
        long elapsed = System.nanoTime() - started;
        return new Report(host, port, participants, rooms, handshakes.sum(), handshakesFailed.sum(), requests.sum(), requestsFailed.sum(),
                matches.sum(), completed.sum(), cancelled.sum(), errors.sum(), timeouts.sum(), elapsed,
                percentileMillis(handshakeLatencies, .50), percentileMillis(handshakeLatencies, .95),
                percentileMillis(matchLatencies, .50), percentileMillis(matchLatencies, .95),
                elapsed == 0 ? 0 : completed.sum() * 1_000_000_000.0 / elapsed);
    }

    private static long percentileMillis(Collection<Long> values, double percentile) {
        if (values.isEmpty()) return 0;
        long[] sorted = values.stream().mapToLong(Long::longValue).sorted().toArray();
        return TimeUnit.NANOSECONDS.toMillis(sorted[Math.min(sorted.length - 1, (int) Math.ceil(percentile * sorted.length) - 1)]);
    }

    public record Report(String host, int port, int participants, int rooms, long handshakes, long handshakeFailures,
                         long successfulRequests, long failedRequests, long matches, long completedMatches,
                         long cancelledMatches, long errors, long timeouts, long elapsedNanos, long handshakeP50Millis,
                         long handshakeP95Millis, long matchP50Millis, long matchP95Millis,
                         double completedMatchesPerSecond) { }
}
