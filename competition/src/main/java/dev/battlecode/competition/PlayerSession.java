package dev.battlecode.competition;
import java.util.*;
public record PlayerSession(UUID sessionId, String displayName, SessionStatus status) {
    public PlayerSession {
        Objects.requireNonNull(sessionId); Objects.requireNonNull(status); Objects.requireNonNull(displayName);
        if (displayName.isBlank() || displayName.length() > 32) throw new IllegalArgumentException("Invalid display name");
    }
}
