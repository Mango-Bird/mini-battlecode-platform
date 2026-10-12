package dev.battlecode.network;
public record NetworkConfig(String bindAddress, int port, int maxConnections, int handshakeTimeoutMillis, int idleTimeoutMillis, int frameTimeoutMillis) {
    public NetworkConfig {
        if (bindAddress == null || bindAddress.isBlank() || port < 0 || port > 65535 || maxConnections < 1
            || handshakeTimeoutMillis < 1 || idleTimeoutMillis < 1 || frameTimeoutMillis < 1)
            throw new IllegalArgumentException("Invalid network config");
    }
    public static NetworkConfig defaults() { return new NetworkConfig("127.0.0.1", 7777, 256, 5000, 60000, 10000); }
}
