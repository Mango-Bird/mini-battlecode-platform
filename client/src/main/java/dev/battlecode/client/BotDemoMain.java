package dev.battlecode.client;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Creates one room and runs two independent TCP bots against the configured server. */
public final class BotDemoMain {
    private BotDemoMain() { }
    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3) {
            System.err.println("Usage: BotDemoMain <host> <port> [rps|number-guessing]");
            System.exit(2);
        }
        String rule = args.length == 3 ? args[2] : "number-guessing";
        int port = Integer.parseInt(args[1]);
        try (var first = new BotClient(); var second = new BotClient(); var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            first.connect(args[0], port, "Bot-A");
            second.connect(args[0], port, "Bot-B");
            String room = first.createRoom(rule, 100, 10_000, rule.equals("number-guessing") ? Map.of("minNumber", "1", "maxNumber", "16") : Map.of("roundsToWin", "1"));
            second.joinRoom(room, rule);
            String match = first.startMatch();
            System.out.printf("Created room %s, match %s (%s)%n", room, match, rule);
            var a = workers.submit(() -> first.playUntilTerminal(BotMain.strategy(rule, 101), Duration.ofSeconds(45)));
            var b = workers.submit(() -> second.playUntilTerminal(BotMain.strategy(rule, 202), Duration.ofSeconds(45)));
            var resultA = a.get(50, TimeUnit.SECONDS); var resultB = b.get(50, TimeUnit.SECONDS);
            System.out.printf("Bot A: %s %s winners=%s reason=%s%n", resultA.matchId(), resultA.status(), resultA.winners(), resultA.reason());
            System.out.printf("Bot B: %s %s winners=%s reason=%s%n", resultB.matchId(), resultB.status(), resultB.winners(), resultB.reason());
        }
    }
}
