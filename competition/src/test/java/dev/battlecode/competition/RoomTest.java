package dev.battlecode.competition;
import dev.battlecode.rulesapi.GameConfig;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class RoomTest {
    @Test void validatesMembershipAndCapacity() {
        UUID owner = UUID.randomUUID(); var config = new GameConfig(2, 10, 1000, Map.of());
        assertThrows(IllegalArgumentException.class, () -> new Room(UUID.randomUUID(), owner, "rule", config, RoomStatus.WAITING, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new Room(UUID.randomUUID(), owner, "rule", config, RoomStatus.WAITING, Set.of(owner, UUID.randomUUID(), UUID.randomUUID())));
    }
    @Test void snapshotsDoNotShareMutableMembers() {
        UUID owner = UUID.randomUUID(); var members = new HashSet<>(Set.of(owner));
        var room = new Room(UUID.randomUUID(), owner, "rule", new GameConfig(2, 10, 1000, Map.of()), RoomStatus.WAITING, members);
        members.clear(); assertEquals(Set.of(owner), room.players()); assertThrows(UnsupportedOperationException.class, () -> room.players().clear());
    }
    @Test void validatesSessionName() {
        assertThrows(IllegalArgumentException.class, () -> new PlayerSession(UUID.randomUUID(), " ", SessionStatus.LOBBY));
    }
}
