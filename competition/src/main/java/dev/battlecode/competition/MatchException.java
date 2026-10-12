package dev.battlecode.competition;

public final class MatchException extends RuntimeException {
    public enum Reason { NOT_OWNER, INVALID_STATE, INTERNAL_ERROR }
    private final Reason reason;
    public MatchException(Reason reason, String message) { super(message); this.reason=reason; }
    public Reason reason() { return reason; }
}
