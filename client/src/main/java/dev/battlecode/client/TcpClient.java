package dev.battlecode.client;
import dev.battlecode.protocol.Message;
import java.io.IOException;
public interface TcpClient extends AutoCloseable {
    void connect(String host, int port) throws IOException;
    Message request(Message message) throws IOException;
    void close() throws IOException;
}
