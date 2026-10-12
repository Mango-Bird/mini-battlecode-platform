package dev.battlecode.client;

import java.util.Map;
import java.util.Objects;
import java.util.SplittableRandom;

/** Built-in strategies that use only the existing private/public event view. */
public final class BotStrategies {
    private BotStrategies() { }

    public static BotStrategy rps(long seed) {
        var random = new SplittableRandom(seed);
        return context -> {
            if (!context.ruleId().equals("rock-paper-scissors")) throw new IllegalArgumentException("RPS strategy used for another rule");
            String submitted = context.playerView().get("submitted." + context.sessionId());
            if (!"false".equals(submitted)) return Map.of();
            String[] choices = {"ROCK", "PAPER", "SCISSORS"};
            return Map.of("choice", choices[random.nextInt(choices.length)]);
        };
    }

    public static BotStrategy numberGuessing() {
        return context -> {
            if (!context.ruleId().equals("number-guessing")) throw new IllegalArgumentException("Number strategy used for another rule");
            Map<String, String> view = context.playerView();
            if (!Boolean.parseBoolean(view.getOrDefault("yourTurn", "false"))) return Map.of();
            long low = parse(view, "minNumber"), high = parse(view, "maxNumber");
            int count = Math.toIntExact(parse(view, "historyCount"));
            for (int i = 0; i < count; i++) {
                long guess = parse(view, "history." + i + ".guess");
                String hint = Objects.requireNonNull(view.get("history." + i + ".hint"), "missing history hint");
                if (hint.equals("HIGHER")) low = Math.max(low, guess + 1);
                else if (hint.equals("LOWER")) high = Math.min(high, guess - 1);
            }
            if (low > high) throw new IllegalArgumentException("Inconsistent public guess history");
            return Map.of("guess", Long.toString(low + (high - low) / 2));
        };
    }

    private static long parse(Map<String, String> view, String key) {
        return Long.parseLong(Objects.requireNonNull(view.get(key), "missing view field " + key));
    }
}
