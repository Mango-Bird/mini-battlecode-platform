package dev.battlecode.client;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BotStrategiesTest {
    @Test void rpsActsOnlyForAnUnsubmittedPlayer() {
        var strategy = BotStrategies.rps(7);
        var pending = new BotMatchContext("rock-paper-scissors", "self", "match", 0, Map.of("submitted.self", "false"));
        assertTrue(java.util.Set.of("ROCK", "PAPER", "SCISSORS").contains(strategy.chooseAction(pending).get("choice")));
        assertTrue(strategy.chooseAction(new BotMatchContext("rock-paper-scissors", "self", "match", 0,
                Map.of("submitted.self", "true"))).isEmpty());
    }

    @Test void numberStrategyNarrowsFromPublicHintsWithoutSecret() {
        var context = new BotMatchContext("number-guessing", "self", "match", 2, Map.of(
                "yourTurn", "true", "minNumber", "1", "maxNumber", "100", "historyCount", "2",
                "history.0.guess", "50", "history.0.hint", "LOWER",
                "history.1.guess", "25", "history.1.hint", "HIGHER"));
        assertEquals(Map.of("guess", "37"), BotStrategies.numberGuessing().chooseAction(context));
    }

    @Test void numberStrategyWaitsWhenItIsNotItsTurn() {
        assertTrue(BotStrategies.numberGuessing().chooseAction(new BotMatchContext("number-guessing", "self", "match", 0,
                Map.of("yourTurn", "false"))).isEmpty());
    }

    @Test void benchmarkRejectsUnboundedOrOddProfiles() {
        assertThrows(IllegalArgumentException.class, () -> BenchmarkRunner.run("localhost", 7777, 1, java.time.Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkRunner.run("localhost", 7777, 52, java.time.Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkRunner.run("localhost", 7777, 2, java.time.Duration.ofMinutes(6)));
    }
}
