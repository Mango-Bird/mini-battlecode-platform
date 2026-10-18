package dev.battlecode.botrunner;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
/** Deterministic test double. Never executes JavaScript. */
public final class FakeBotRunner implements BotRunner {
    private final Function<BotExecutionRequest,CompletionStage<BotExecutionResult>> behavior;
    private final Map<UUID,CompletableFuture<BotExecutionResult>> pending=new ConcurrentHashMap<>();
    public FakeBotRunner(Function<BotExecutionRequest,CompletionStage<BotExecutionResult>> behavior) { this.behavior=Objects.requireNonNull(behavior); }
    public CompletionStage<BotExecutionResult> execute(BotExecutionRequest request) {
        var result=behavior.apply(request).toCompletableFuture();pending.put(request.executionId(),result);
        result.whenComplete((r,e)->pending.remove(request.executionId(),result));return result;
    }
    public CompletionStage<Void> cancel(UUID id) {
        var result=pending.get(id);if(result!=null)result.complete(BotExecutionResult.failed(id,BotExecutionFailure.Code.MATCH_CANCELLED,"FAKE_CANCELLED"));
        return CompletableFuture.completedFuture(null);
    }
    public void close() { List.copyOf(pending.keySet()).forEach(this::cancel); }
    public String runtimeFingerprint() { return "FAKE_TEST_ONLY_NO_JAVASCRIPT"; }
}
