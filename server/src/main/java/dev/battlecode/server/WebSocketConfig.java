package dev.battlecode.server;

import java.net.URI;
import java.util.Set;

/** Public transport configuration. Existing RoomTcpServer constructors remain TCP-only. */
public record WebSocketConfig(String bindAddress, int port, Set<String> allowedOrigins,
                              int outboundCapacity, int writeTimeoutMillis) {
    public WebSocketConfig {
        if (bindAddress == null || bindAddress.isBlank() || port < 0 || port > 65535
                || outboundCapacity < 1 || outboundCapacity > 256 || writeTimeoutMillis < 50)
            throw new IllegalArgumentException("Invalid WebSocket limits");
        allowedOrigins = Set.copyOf(allowedOrigins);
        if (allowedOrigins.isEmpty()) throw new IllegalArgumentException("Origin allowlist is required");
        for (String origin : allowedOrigins) {
            URI uri = URI.create(origin);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !uri.getPath().isEmpty()) throw new IllegalArgumentException("Use exact HTTP(S) origins without paths/wildcards");
        }
    }
    public static WebSocketConfig local() {
        return new WebSocketConfig("127.0.0.1", 8081,
                Set.of("http://localhost:5173", "http://127.0.0.1:5173"), 256, 10_000);
    }
}
