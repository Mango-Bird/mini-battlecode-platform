package dev.battlecode.protocol;
import java.util.*;
public record Message(String version, String requestId, String sessionId, Command command, ErrorCode errorCode, Map<String,String> payload) {
    public static final String VERSION = "1.0";
    public Message {
        Objects.requireNonNull(version); Objects.requireNonNull(requestId); Objects.requireNonNull(sessionId);
        Objects.requireNonNull(command); Objects.requireNonNull(errorCode);
        if (version.isEmpty() || version.length() > 16 || requestId.length() > 64 || sessionId.length() > 64)
            throw new IllegalArgumentException("Invalid envelope length");
        if (command == Command.ERROR ? errorCode == ErrorCode.NONE : errorCode != ErrorCode.NONE)
            throw new IllegalArgumentException("Error code inconsistent with command");
        payload = Map.copyOf(payload);
        if (payload.size() > 128) throw new IllegalArgumentException("Too many payload fields");
    }
}
