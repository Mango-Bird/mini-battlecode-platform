package dev.battlecode.client;

import java.time.Duration;
import java.util.Locale;

/** Join an existing room and run a built-in strategy. */
public final class BotMain {
    private BotMain() { }
    public static void main(String[] args) throws Exception {
        if (args.length != 6) {
            System.err.println("Usage: BotMain <host> <port> <name> <roomId> <ruleId> <rps|number-guessing>");
            System.exit(2);
        }
        String host = args[0], name = args[2], room = args[3], rule = args[4];
        int port = Integer.parseInt(args[1]);
        BotStrategy strategy = strategy(args[5], name.hashCode());
        try (var bot = new BotClient()) {
            bot.connect(host, port, name);
            bot.joinRoom(room, rule);
            var result = bot.playUntilTerminal(strategy, Duration.ofMinutes(5));
            System.out.printf("Bot match %s %s winners=%s reason=%s%n", result.matchId(), result.status(), result.winners(), result.reason());
        }
    }

    static BotStrategy strategy(String value, long seed) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "rps" -> BotStrategies.rps(seed);
            case "number-guessing" -> BotStrategies.numberGuessing();
            default -> throw new IllegalArgumentException("Supported strategies: rps, number-guessing");
        };
    }
}
