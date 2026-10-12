package dev.battlecode.network;
import dev.battlecode.protocol.Message;
import java.util.UUID;
/** Application adapter implemented in the future server composition module. */
public interface RequestDispatcher {
    Message dispatch(UUID connectionId, Message request);
    void disconnected(UUID connectionId);
}
