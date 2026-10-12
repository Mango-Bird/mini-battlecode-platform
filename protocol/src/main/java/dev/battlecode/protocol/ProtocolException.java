package dev.battlecode.protocol;
import java.io.IOException;
public final class ProtocolException extends IOException {
    private final ErrorCode code;
    public ProtocolException(ErrorCode code, String message) { super(message); this.code = code; }
    public ErrorCode code() { return code; }
}
