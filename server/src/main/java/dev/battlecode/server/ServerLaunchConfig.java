package dev.battlecode.server;

import dev.battlecode.network.NetworkConfig;
import java.util.*;

/** Parses the real process environment separately so deployment contracts can be tested. */
record ServerLaunchConfig(ServerMode mode, NetworkConfig network, WebSocketConfig web) {
    static ServerLaunchConfig parse(String[] args, Map<String,String> env) {
        if (args.length > 6) throw new IllegalArgumentException("Usage: ServerMain [bindIp] [port] [maxConnections] [helloMs] [idleMs] [frameMs]");
        ServerMode mode = ServerMode.valueOf(env.getOrDefault("BATTLECODE_MODE", "LOCAL"));
        var defaults = NetworkConfig.defaults();
        var network = new NetworkConfig(args.length > 0 ? args[0] : defaults.bindAddress(),
            number(args,1,defaults.port()), number(args,2,mode == ServerMode.WEB_ONLY ? 16 : defaults.maxConnections()),
            number(args,3,defaults.handshakeTimeoutMillis()), number(args,4,defaults.idleTimeoutMillis()),
            number(args,5,defaults.frameTimeoutMillis()));
        String port = mode == ServerMode.WEB_ONLY ? required(env,"PORT") : env.get("BATTLECODE_WS_PORT");
        if (mode == ServerMode.WEB_ONLY && (Integer.parseInt(port) < 1 || Integer.parseInt(port) > 65535))
            throw new IllegalArgumentException("PORT must be 1..65535 in WEB_ONLY");
        String origins = mode == ServerMode.WEB_ONLY ? required(env,"BATTLECODE_ORIGINS")
            : env.getOrDefault("BATTLECODE_ORIGINS","http://localhost:5173,http://127.0.0.1:5173");
        var allowed = new HashSet<String>();
        for (String origin : origins.split(",", -1)) allowed.add(origin.trim());
        var web = port == null ? null : new WebSocketConfig(
            mode == ServerMode.WEB_ONLY ? "0.0.0.0" : env.getOrDefault("BATTLECODE_WS_BIND",network.bindAddress()),
            Integer.parseInt(port), allowed, mode == ServerMode.WEB_ONLY ? 32 : 256, 10_000);
        return new ServerLaunchConfig(mode,network,web);
    }
    private static String required(Map<String,String> env,String key) {
        String value=env.get(key);
        if(value==null || value.isBlank())throw new IllegalArgumentException(key+" is required in WEB_ONLY");
        return value;
    }
    private static int number(String[] args,int index,int fallback) {
        return args.length>index?Integer.parseInt(args[index]):fallback;
    }
}
