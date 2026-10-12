package dev.battlecode.server;

import dev.battlecode.competition.RoomNotification;
import dev.battlecode.competition.MatchNotification;
import dev.battlecode.competition.Lobby;
import dev.battlecode.competition.RoomDetails;
import dev.battlecode.network.*;
import dev.battlecode.protocol.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Single consumer preserves mutation order. publish never performs I/O or waits under the Lobby lock. */
final class RoomEventPublisher {
    private record RoomUpdate(UUID roomId, long revision, RoomDetails details, boolean before) { }
    private record Batch(Map<UUID,Message> messages, Map<UUID,RoomUpdate> rooms) { }
    private Lobby lobby;
    void attachLobby(Lobby lobby) { this.lobby = lobby; }
    private final ArrayBlockingQueue<Batch> queue = new ArrayBlockingQueue<>(4096);
    private final Set<UUID> overflow = ConcurrentHashMap.newKeySet();
    private final ConnectionManager connections;
    private final ConcurrentMap<UUID,UUID> routes;
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("battlecode-room-events").factory());

    RoomEventPublisher(ConnectionManager connections, ConcurrentMap<UUID,UUID> routes) {
        this.connections = connections; this.routes = routes; worker.execute(this::run);
    }
    void publish(RoomNotification event) {
        var messages=new HashMap<UUID,Message>();
        for(UUID session:event.recipients())messages.put(session,new Message("1.0","",session.toString(),Command.ROOM_EVENT,ErrorCode.NONE,
            Map.of("type",event.type(),"roomId",event.roomId().toString(),"status",event.status().name(),"players",Integer.toString(event.players()))));
        var rooms = new HashMap<UUID,RoomUpdate>();
        if (event.details() != null) for (UUID session : event.recipients())
            rooms.put(session, new RoomUpdate(event.roomId(), event.details().revision(), event.details(), false));
        enqueue(messages, rooms);
    }
    void publish(MatchNotification event) {
        var messages=new HashMap<UUID,Message>();
        event.snapshots().forEach((session,snapshot)->{
            var payload=new HashMap<String,String>(); payload.put("type",event.type()); payload.put("matchId",snapshot.matchId().toString()); payload.put("turn",Integer.toString(snapshot.turn()));
            if(event.type().equals("STARTED") || event.type().equals("STATE")) {
                payload.put("deadlineEpochMillis",Long.toString(snapshot.deadlineEpochMillis())); snapshot.view().forEach((key,value)->payload.put("view."+key,value));
            } else {
                payload.put("reason",event.result().reason());
                if(event.type().equals("FINISHED"))payload.put("winners",event.result().winners().stream().sorted().map(UUID::toString).collect(java.util.stream.Collectors.joining(",")));
            }
            messages.put(session,new Message("1.0","",session.toString(),Command.MATCH_EVENT,ErrorCode.NONE,payload));
        });
        var rooms = new HashMap<UUID,RoomUpdate>();
        if (event.type().equals("STARTED") && lobby != null) for (UUID session : messages.keySet())
            lobby.roomDetails(session).ifPresent(details -> rooms.put(session, new RoomUpdate(details.roomId(), details.revision(), details, true)));
        enqueue(messages, rooms);
    }
    private void enqueue(Map<UUID,Message> messages, Map<UUID,RoomUpdate> rooms) {
        if (accepting.get() && !queue.offer(new Batch(Map.copyOf(messages), Map.copyOf(rooms)))) overflow.addAll(messages.keySet());
    }
    private void run() {
        try {
            while (accepting.get()) {
                for (UUID session : List.copyOf(overflow)) {
                    overflow.remove(session); connection(session).ifPresent(this::disconnect);
                }
                var event = queue.poll(100, TimeUnit.MILLISECONDS);
                if (event == null) continue;
                for (UUID session : event.messages().keySet()) {
                    var message = event.messages().get(session);
                    connection(session).ifPresent(connection -> {
                        try {
                            var room = event.rooms().get(session);
                            if (room != null && room.before() && connection instanceof WebRoomConnection browser)
                                browser.roomState(session, room.roomId(), room.revision(), room.details());
                            connection.send(message);
                            if (room != null && !room.before() && connection instanceof WebRoomConnection browser)
                                browser.roomState(session, room.roomId(), room.revision(), room.details());
                        } catch (IOException ex) { disconnect(connection); }
                    });
                }
            }
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }
    private Optional<ConnectionHandler> connection(UUID session) {
        UUID id = routes.get(session); return id == null ? Optional.empty() : connections.find(id);
    }
    private void disconnect(ConnectionHandler connection) { try { connection.close(); } catch (IOException ignored) { } }
    void stop() { accepting.set(false); worker.shutdownNow(); }
    void awaitClosed(long deadline) throws IOException {
        try {
            if (!worker.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS))
                throw new IOException("Room event publisher shutdown deadline exceeded");
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException("Event shutdown interrupted", ex); }
        finally { queue.clear(); overflow.clear(); }
    }
    boolean isTerminated() { return worker.isTerminated(); }
}
