package dev.battlecode.network;
import java.util.*;
public interface ConnectionManager extends AutoCloseable {
    void register(ConnectionHandler connection);
    Optional<ConnectionHandler> find(UUID connectionId);
    void remove(UUID connectionId);
    int size();
    void close();
}
