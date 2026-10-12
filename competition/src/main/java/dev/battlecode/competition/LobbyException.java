package dev.battlecode.competition;

/** Domain failure; server translates it to the existing protocol error codes. */
public final class LobbyException extends RuntimeException {
    public enum Reason { INVALID_SESSION, INVALID_STATE, RULE_NOT_FOUND, ROOM_NOT_FOUND, ROOM_FULL, INVALID_CONFIG }
    private final Reason reason;
    public LobbyException(Reason reason, String message) { super(message); this.reason = reason; }
    public Reason reason() { return reason; }
}
