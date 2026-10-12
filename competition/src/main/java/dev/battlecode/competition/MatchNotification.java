package dev.battlecode.competition;

import dev.battlecode.engine.*;
import java.util.*;

/** Per-recipient private snapshots, no socket or protocol dependency. */
public record MatchNotification(String type, Map<UUID,PlayerSnapshot> snapshots, GameResult result) {
    public MatchNotification { snapshots = Map.copyOf(snapshots); }
}
