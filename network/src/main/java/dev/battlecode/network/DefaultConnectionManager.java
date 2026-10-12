package dev.battlecode.network;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Admission and shutdown are synchronized so no connection can escape cleanup. */
public final class DefaultConnectionManager implements ConnectionManager {
    private final Map<UUID, ConnectionHandler> connections = new ConcurrentHashMap<>();
    private final int capacity;
    private boolean closed;

    public DefaultConnectionManager(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Capacity must be positive");
        this.capacity = capacity;
    }

    public synchronized void register(ConnectionHandler connection) {
        Objects.requireNonNull(connection);
        if (closed || connections.size() >= capacity) throw new IllegalStateException("Connection admission closed/full");
        if (connections.putIfAbsent(connection.connectionId(), connection) != null)
            throw new IllegalArgumentException("Duplicate connection");
    }

    public Optional<ConnectionHandler> find(UUID id) { return Optional.ofNullable(connections.get(id)); }
    public void remove(UUID id) { connections.remove(id); }
    public int size() { return connections.size(); }
    List<ConnectionHandler> snapshot() { return List.copyOf(connections.values()); }

    public void close() {
        List<ConnectionHandler> snapshot;
        synchronized (this) { closed = true; snapshot = snapshot(); }
        for (var connection : snapshot) {
            try { connection.close(); }
            catch (IOException ignored) { /* Continue closing the remaining connections. */ }
        }
    }
}
