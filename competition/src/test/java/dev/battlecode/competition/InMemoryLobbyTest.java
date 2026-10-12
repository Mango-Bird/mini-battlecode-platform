package dev.battlecode.competition;

import dev.battlecode.rulesapi.*;
import org.junit.jupiter.api.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(8)
class InMemoryLobbyTest {
    private InMemoryLobby lobby;
    private final List<RoomNotification> events = new CopyOnWriteArrayList<>();
    private final GameConfig config = new GameConfig(2, 10, 1000, Map.of());

    @BeforeEach void setup() {
        GameRule descriptor = (GameRule) Proxy.newProxyInstance(GameRule.class.getClassLoader(), new Class[]{GameRule.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "id" -> "test-descriptor";
                case "validateConfig" -> null;
                default -> throw new UnsupportedOperationException("No test gameplay");
            });
        lobby = new InMemoryLobby(new InMemoryRuleRegistry(List.of(descriptor)), events::add);
    }
    private UUID register() { UUID id = UUID.randomUUID(); lobby.register(id, "Test"); return id; }
    private Room create(UUID id) { return lobby.createRoom(id, "test-descriptor", config); }
    @Test void roomDetailsIsMemberAuthorizedImmutableAndEventTimeSnapshot() {
        UUID host=register(),guest=register(),outside=register();var room=create(host);
        var created=events.getLast().details();assertEquals(1,created.players().size());
        var old=new RoomNotification("CREATED",room.roomId(),RoomStatus.WAITING,1,Set.of(host));
        assertEquals(old,events.getLast());assertEquals(old.hashCode(),events.getLast().hashCode());
        assertTrue(lobby.roomDetails(outside).isEmpty());
        assertThrows(LobbyException.class,()->lobby.roomDetails(UUID.randomUUID()));
        lobby.joinRoom(guest,room.roomId());var joined=events.getLast().details();lobby.leaveRoom(host);
        var migrated=lobby.roomDetails(guest).orElseThrow();assertEquals(guest,migrated.hostId());
        assertTrue(lobby.roomDetails(host).isEmpty());assertEquals(2,joined.players().size());assertEquals(host,joined.hostId());
        assertEquals(1,created.players().size());assertTrue(migrated.revision()>joined.revision());
        assertThrows(UnsupportedOperationException.class,()->migrated.players().clear());
        assertNull(new RoomNotification("LEFT",room.roomId(),RoomStatus.WAITING,1,Set.of(guest)).details());
    }
    @Test void concurrentRoomSnapshotsNeverMixHostAndMembership() throws Exception {
        UUID host=register(),guest=register();var room=create(host);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var mutate=executor.submit(()->{for(int i=0;i<100;i++){lobby.joinRoom(guest,room.roomId());lobby.leaveRoom(guest);}});
            var read=executor.submit(()->{long revision=0;for(int i=0;i<200;i++){var snapshot=lobby.roomDetails(host).orElseThrow();assertTrue(snapshot.contains(snapshot.hostId()));assertTrue(snapshot.players().size()<=snapshot.capacity());assertTrue(snapshot.revision()>=revision);revision=snapshot.revision();}});
            mutate.get(3,TimeUnit.SECONDS);read.get(3,TimeUnit.SECONDS);
        }
    }
    private Object result(Callable<?> operation) throws Exception {
        try { return operation.call(); } catch (LobbyException ex) { return ex.reason(); }
    }
    private List<Object> race(Callable<?> first, Callable<?> second) throws Exception {
        var executor = Executors.newVirtualThreadPerTaskExecutor(); var barrier = new CyclicBarrier(3);
        try {
            var a = executor.submit(() -> { barrier.await(2, TimeUnit.SECONDS); return result(first); });
            var b = executor.submit(() -> { barrier.await(2, TimeUnit.SECONDS); return result(second); });
            barrier.await(2, TimeUnit.SECONDS);
            return List.of(a.get(3, TimeUnit.SECONDS), b.get(3, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS)); }
    }

    @Test void tracksLifecycleAndImmutableSnapshots() {
        UUID host = register(), guest = register(); Room initial = create(host);
        lobby.joinRoom(guest, initial.roomId());
        assertEquals(1, initial.players().size()); assertEquals(SessionStatus.IN_ROOM, lobby.session(guest).orElseThrow().status());
        lobby.leaveRoom(guest); assertEquals(SessionStatus.LOBBY, lobby.session(guest).orElseThrow().status());
        lobby.leaveRoom(host); assertTrue(lobby.rooms().isEmpty());
        assertEquals(List.of("CREATED", "JOINED", "LEFT", "CLOSED"), events.stream().map(RoomNotification::type).toList());
        assertEquals(0, events.getLast().players()); assertEquals(RoomStatus.CLOSED, events.getLast().status());
        assertThrows(UnsupportedOperationException.class, () -> initial.players().clear());
    }

    @Test void transfersHostInAcceptanceOrderAndDeletesEmptyRoom() {
        UUID host = register(), first = register(), second = register();
        var room = lobby.createRoom(host, "test-descriptor", new GameConfig(3, 10, 1000, Map.of()));
        lobby.joinRoom(first, room.roomId()); lobby.joinRoom(second, room.roomId());
        lobby.leaveRoom(host); assertEquals(first, lobby.rooms().getFirst().ownerId());
        lobby.disconnect(first); assertEquals(second, lobby.rooms().getFirst().ownerId());
        lobby.disconnect(second); assertTrue(lobby.rooms().isEmpty());
    }

    @Test void disconnectIsIdempotentAndClosedSessionCannotAct() {
        UUID host = register(), guest = register(); var room = create(host); lobby.joinRoom(guest, room.roomId());
        lobby.disconnect(guest); int count = events.size(); lobby.disconnect(guest);
        assertEquals(count, events.size()); assertTrue(lobby.session(guest).isEmpty());
        assertEquals(LobbyException.Reason.INVALID_SESSION, assertThrows(LobbyException.class, () -> lobby.joinRoom(guest, room.roomId())).reason());
        assertEquals(Set.of(host), lobby.rooms().getFirst().players());
    }

    @Test void exactlyOneJoinWinsFinalSlot() throws Exception {
        UUID host = register(), a = register(), b = register(); var room = create(host);
        var results = race(() -> lobby.joinRoom(a, room.roomId()), () -> lobby.joinRoom(b, room.roomId()));
        assertEquals(1, results.stream().filter(Room.class::isInstance).count());
        assertTrue(results.contains(LobbyException.Reason.ROOM_FULL)); assertEquals(2, lobby.rooms().getFirst().players().size());
    }

    @Test void sessionCannotJoinTwoRoomsEvenConcurrently() throws Exception {
        UUID hostA = register(), hostB = register(), guest = register(); var a = create(hostA); var b = create(hostB);
        var results = race(() -> lobby.joinRoom(guest, a.roomId()), () -> lobby.joinRoom(guest, b.roomId()));
        assertEquals(1, results.stream().filter(Room.class::isInstance).count()); assertTrue(results.contains(LobbyException.Reason.INVALID_STATE));
        assertEquals(1, lobby.rooms().stream().filter(room -> room.players().contains(guest)).count());
    }

    @Test void joinRacingDisconnectNeverLeavesGhostMember() throws Exception {
        for (int i = 0; i < 20; i++) {
            UUID host = register(), guest = register(); var room = create(host);
            race(() -> lobby.joinRoom(guest, room.roomId()), () -> { lobby.disconnect(guest); return "closed"; });
            assertTrue(lobby.session(guest).isEmpty()); assertFalse(lobby.rooms().stream().anyMatch(r -> r.players().contains(guest)));
            lobby.disconnect(host);
        }
        assertEquals(0, lobby.sessionCount()); assertTrue(lobby.rooms().isEmpty());
    }

    @Test void hostLeaveRacingJoinIsSerializable() throws Exception {
        for (int i = 0; i < 20; i++) {
            UUID host = register(), guest = register(); var room = create(host);
            var results = race(() -> lobby.joinRoom(guest, room.roomId()), () -> { lobby.leaveRoom(host); return "left"; });
            if (results.getFirst() instanceof Room) {
                assertEquals(guest, lobby.rooms().getFirst().ownerId()); assertEquals(Set.of(guest), lobby.rooms().getFirst().players());
            } else { assertEquals(LobbyException.Reason.ROOM_NOT_FOUND, results.getFirst()); assertTrue(lobby.rooms().isEmpty()); }
            lobby.disconnect(host); lobby.disconnect(guest);
        }
    }

    @Test void duplicateJoinAndMissingRulesDoNotMutateState() {
        UUID host = register(), guest = register(); var room = create(host); lobby.joinRoom(guest, room.roomId());
        assertEquals(LobbyException.Reason.INVALID_STATE, assertThrows(LobbyException.class, () -> lobby.joinRoom(guest, room.roomId())).reason());
        UUID other = register();
        assertEquals(LobbyException.Reason.RULE_NOT_FOUND, assertThrows(LobbyException.class, () -> lobby.createRoom(other, "unknown", config)).reason());
        assertEquals(1, lobby.rooms().size()); assertEquals(2, lobby.rooms().getFirst().players().size());
        assertEquals(SessionStatus.LOBBY, lobby.session(other).orElseThrow().status());
    }
}
