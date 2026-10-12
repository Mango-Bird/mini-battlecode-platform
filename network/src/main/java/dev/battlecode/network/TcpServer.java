package dev.battlecode.network;
import java.io.IOException;
public interface TcpServer extends AutoCloseable {
    void start() throws IOException;
    int localPort();
    void close() throws IOException;
}
