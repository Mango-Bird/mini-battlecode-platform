package dev.battlecode.network;
import dev.battlecode.protocol.Message;
import java.io.IOException;
import java.util.UUID;
public interface ConnectionHandler extends AutoCloseable {
    UUID connectionId();
    ConnectionState state();
    void send(Message message) throws IOException;
    void close() throws IOException;
}
