package dev.battlecode.server;

import java.util.concurrent.CountDownLatch;

public final class ServerMain {
    private ServerMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length > 6) {
            System.err.println("Usage: ServerMain [bindIp] [port] [maxConnections] [helloMs] [idleMs] [frameMs]");
            return;
        }
        var launch = ServerLaunchConfig.parse(args,System.getenv());
        var config = launch.network();
        var stopped = new CountDownLatch(1);
        WebSocketConfig web = launch.web();
        try (var server = new RoomTcpServer(config, new dev.battlecode.rulesapi.InMemoryRuleRegistry(java.util.List.of(
                new dev.battlecode.rulesimpl.RoomOnlyRule(), new dev.battlecode.rulesimpl.RockPaperScissorsRule(), new dev.battlecode.rulesimpl.NumberGuessingRule(), new dev.battlecode.rulesimpl.OttBoardRule())), web, launch.mode())) {
            Thread hook = new Thread(() -> {
                try { server.close(); }
                catch (Exception ex) { System.err.println("Shutdown: " + ex.getMessage()); }
                finally { stopped.countDown(); }
            }, "battlecode-shutdown");
            server.start();
            Runtime.getRuntime().addShutdownHook(hook);
            System.out.println("Server mode: " + launch.mode());
            if (launch.mode() == ServerMode.LOCAL) System.out.printf("Mini Battlecode TCP 1.0 listening on %s:%d%n", config.bindAddress(), server.localPort());
            if (web != null) System.out.printf("WebSocket /ws and HTTP /health on %s:%d%n", web.bindAddress(), server.websocketPort());
            System.out.println("Press Ctrl+C to stop.");
            System.out.println("Production rules: room-only-no-gameplay, rock-paper-scissors, number-guessing, ott-board-9x9-v1.");
            try { stopped.await(); }
            finally {
                try { Runtime.getRuntime().removeShutdownHook(hook); }
                catch (IllegalStateException ignored) { /* JVM is shutting down. */ }
            }
        }
    }

}
