package dev.battlecode.botrunner;
import dev.battlecode.rulesapi.bot.BotObservation;
import java.util.*;
public record BotExecutionRequest(UUID executionId, BotSubmission submission,
                                  BotObservation observation, BotExecutionLimits limits) {
    public BotExecutionRequest {
        Objects.requireNonNull(executionId); Objects.requireNonNull(submission);
        Objects.requireNonNull(observation); Objects.requireNonNull(limits);
        if (observation.remainingTimeMs()!=limits.wallTimeMs()) throw new IllegalArgumentException("Observation budget mismatch");
        if (observation.board().size()>12 || observation.legalMoves().size()>48) throw new IllegalArgumentException("Observation too large");
    }
}
