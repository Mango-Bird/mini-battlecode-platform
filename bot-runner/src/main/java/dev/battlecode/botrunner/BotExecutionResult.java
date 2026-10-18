package dev.battlecode.botrunner;
import dev.battlecode.rulesapi.bot.BotAction;
import java.util.*;
public record BotExecutionResult(UUID executionId, BotAction action, BotExecutionFailure failure, long elapsedTimeMs) {
    public BotExecutionResult {
        Objects.requireNonNull(executionId);
        if ((action==null)==(failure==null) || elapsedTimeMs<0) throw new IllegalArgumentException("Result must have action XOR failure");
    }
    public static BotExecutionResult failed(UUID id, BotExecutionFailure.Code code, String reason) {
        return new BotExecutionResult(id, null, new BotExecutionFailure(code,reason),0);
    }
}
