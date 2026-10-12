package dev.battlecode.server;

import dev.battlecode.protocol.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NetworkRequestDispatcherTest {
    @Test void validatesSchemasBeforeReturningBusinessErrors() {
        var dispatcher = new NetworkRequestDispatcher(); UUID connection = UUID.randomUUID();
        Message hello = dispatcher.dispatch(connection, new Message("1.0", "h", "", Command.HELLO, ErrorCode.NONE, Map.of("name", "A")));
        String session = hello.sessionId();
        var config = Map.of("ruleId", "absent", "maxPlayers", "2", "maxTurns", "10", "turnTimeoutMillis", "1000");
        assertEquals(ErrorCode.RULE_NOT_FOUND, dispatcher.dispatch(connection,
            new Message("1.0", "c", session, Command.CREATE_ROOM, ErrorCode.NONE, config)).errorCode());
        assertEquals(ErrorCode.INVALID_PAYLOAD, dispatcher.dispatch(connection,
            new Message("1.0", "l", session, Command.LIST_ROOMS, ErrorCode.NONE, Map.of("limit", "11"))).errorCode());
        assertEquals(ErrorCode.INVALID_PAYLOAD, dispatcher.dispatch(connection,
            new Message("1.0", "j", session, Command.JOIN_ROOM, ErrorCode.NONE, Map.of("roomId", "1-1-1-1-1"))).errorCode());
        dispatcher.disconnected(connection); assertEquals(0, dispatcher.sessionCount());
    }

    @Test void sessionCannotBeUsedOnAnotherConnection() {
        var dispatcher = new NetworkRequestDispatcher();
        Message first = dispatcher.dispatch(UUID.randomUUID(), new Message("1.0", "h", "", Command.HELLO, ErrorCode.NONE, Map.of("name", "A")));
        assertEquals(ErrorCode.INVALID_SESSION, dispatcher.dispatch(UUID.randomUUID(),
            new Message("1.0", "h", first.sessionId(), Command.HELLO, ErrorCode.NONE, Map.of("name", "B"))).errorCode());
    }

    @Test void burstIsRateLimitedAndQuotaBoundsSessionLifetime() {
        var dispatcher = new NetworkRequestDispatcher(); UUID connection = UUID.randomUUID();
        var hello = dispatcher.dispatch(connection, new Message("1.0", "h", "", Command.HELLO, ErrorCode.NONE, Map.of("name", "A")));
        int limited = 0;
        for (int i = 1; i < 10_000; i++) {
            var response = dispatcher.dispatch(connection, new Message("1.0", "p" + i, hello.sessionId(), Command.PING, ErrorCode.NONE, Map.of()));
            if (response.errorCode() == ErrorCode.RATE_LIMITED) limited++;
        }
        assertTrue(limited > 0);
        assertEquals(ErrorCode.INVALID_SESSION, dispatcher.dispatch(connection,
            new Message("1.0", "last", hello.sessionId(), Command.PING, ErrorCode.NONE, Map.of())).errorCode());
        dispatcher.disconnected(connection); assertEquals(0, dispatcher.sessionCount());
    }
}
