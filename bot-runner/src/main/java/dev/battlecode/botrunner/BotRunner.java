package dev.battlecode.botrunner;
import java.util.*;
import java.util.concurrent.*;
/** Cancellation completes only after execution has stopped; execute does not commit a move. */
public interface BotRunner extends AutoCloseable {
    CompletionStage<BotExecutionResult> execute(BotExecutionRequest request);
    CompletionStage<Void> cancel(UUID executionId);
    default String runtimeFingerprint() { return "UNVERIFIED_RUNNER"; }
    @Override void close();
}
