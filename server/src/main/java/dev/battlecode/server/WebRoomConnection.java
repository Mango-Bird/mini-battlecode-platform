package dev.battlecode.server;

import dev.battlecode.competition.RoomDetails;
import java.io.IOException;
import java.util.UUID;

/** Internal transport extension; never changes ConnectionHandler or the TCP envelope. */
interface WebRoomConnection {
    void roomState(UUID session, UUID roomId, long revision, RoomDetails snapshot) throws IOException;
}
