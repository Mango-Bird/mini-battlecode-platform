package dev.battlecode.engine;

public final class EngineException extends RuntimeException {
    public enum Reason { INVALID_STATE, NOT_YOUR_TURN, ACTION_REJECTED, TIMEOUT, INTERNAL_ERROR }
    private final Reason reason;
    public EngineException(Reason reason, String message) { super(message); this.reason = reason; }
    public Reason reason() { return reason; }
}
