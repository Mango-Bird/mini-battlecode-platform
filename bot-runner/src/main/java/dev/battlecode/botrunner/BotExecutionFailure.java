package dev.battlecode.botrunner;
import java.util.*;
public record BotExecutionFailure(Code code, String reason) {
    public enum Code {
        INVALID_ACTION, BOT_TIMEOUT, BOT_RUNTIME_ERROR, BOT_RESOURCE_LIMIT,
        RUNNER_UNAVAILABLE, RUNNER_CRASH, INTERNAL_ERROR, MATCH_CANCELLED;
        public boolean botCaused() { return ordinal() <= BOT_RESOURCE_LIMIT.ordinal(); }
    }
    public BotExecutionFailure {
        Objects.requireNonNull(code); Objects.requireNonNull(reason);
        if (reason.length()>512 || reason.chars().anyMatch(c->c<32)) throw new IllegalArgumentException("Invalid diagnostic");
    }
}
