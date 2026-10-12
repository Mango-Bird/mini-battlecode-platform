package dev.battlecode.server;

import dev.battlecode.competition.*;
import dev.battlecode.network.*;
import dev.battlecode.rulesapi.*;
import dev.battlecode.rulesimpl.NumberGuessingRule;
import dev.battlecode.rulesimpl.RockPaperScissorsRule;
import dev.battlecode.rulesimpl.RoomOnlyRule;
import dev.battlecode.engine.DefaultGameEngine;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Composition root. Public contracts remain TcpServer, Lobby, RuleRegistry and ConnectionManager. */
public final class RoomTcpServer implements TcpServer {
    private final InMemoryLobby lobby;
    private final NetworkRequestDispatcher dispatcher;
    private final DefaultTcpServer transport;
    private final RoomEventPublisher notifications;
    private final DefaultMatchManager matches;
    private final WebSocketGateway web;
    private final DefaultConnectionManager manager;
    private final ConcurrentMap<UUID,UUID> routes = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public RoomTcpServer(NetworkConfig config) {
        this(config, new InMemoryRuleRegistry(List.of(
                new RoomOnlyRule(), new RockPaperScissorsRule(), new NumberGuessingRule())));
    }
    public RoomTcpServer(NetworkConfig config, RuleRegistry registry) {
        this(config, registry, null);
    }
    public RoomTcpServer(NetworkConfig config, RuleRegistry registry, WebSocketConfig webConfig) {
        this(config,registry,webConfig,ServerMode.LOCAL);
    }
    public RoomTcpServer(NetworkConfig config, RuleRegistry registry, WebSocketConfig webConfig, ServerMode mode) {
        Objects.requireNonNull(config); Objects.requireNonNull(registry);
        Objects.requireNonNull(mode);
        if (mode == ServerMode.WEB_ONLY && webConfig == null) throw new IllegalArgumentException("WEB_ONLY requires WebSocket config");
        manager = new DefaultConnectionManager(config.maxConnections());
        notifications = new RoomEventPublisher(manager, routes);
        lobby = new InMemoryLobby(registry, notifications::publish);
        notifications.attachLobby(lobby);
        matches = new DefaultMatchManager(lobby, new DefaultGameEngine(), registry, notifications::publish);
        dispatcher = new NetworkRequestDispatcher(lobby, registry, routes, matches);
        transport = mode == ServerMode.WEB_ONLY ? null : new DefaultTcpServer(config, dispatcher, manager);
        web = webConfig == null ? null : new WebSocketGateway(webConfig, config, manager, dispatcher);
    }
    public void start() throws IOException {
        if (closed.get()) throw new IllegalStateException("Server closed");
        try { if (transport != null) transport.start(); if (web != null) web.start(); } catch (IOException | RuntimeException ex) { close(); throw ex; }
    }
    public int localPort() { if (transport == null) throw new IllegalStateException("TCP disabled in WEB_ONLY"); return transport.localPort(); }
    public int websocketPort() { if (web == null) throw new IllegalStateException("WebSocket disabled"); return web.localPort(); }
    public Lobby lobby() { return lobby; }
    public int sessionCount() { return lobby.sessionCount(); }
    public int connectionCount() { return manager.size(); }
    public boolean isTerminated() { return (transport == null || transport.isTerminated()) && notifications.isTerminated() && matches.isTerminated() && (web == null || web.isTerminated()); }
    public MatchManager matches() { return matches; }
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) return;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        matches.shutdown();
        notifications.stop();
        IOException failure = null;
        try { if (transport != null) transport.close(); else manager.close(); } catch (IOException ex) { failure = ex; }
        try { if (web != null) web.close(); } catch (IOException ex) { if (failure == null) failure = ex; else failure.addSuppressed(ex); }
        try { notifications.awaitClosed(deadline); }
        catch (IOException ex) { if (failure == null) failure = ex; else failure.addSuppressed(ex); }
        finally { routes.clear(); }
        try { if(!matches.awaitTermination(Math.max(0,deadline-System.nanoTime()),TimeUnit.NANOSECONDS))throw new IOException("Match scheduler shutdown deadline exceeded"); }
        catch(IOException ex){if(failure==null)failure=ex;else failure.addSuppressed(ex);}
        catch(InterruptedException ex){Thread.currentThread().interrupt(); if(failure==null)failure=new IOException("Match shutdown interrupted",ex);}
        if (failure != null) throw failure;
    }
}
