package dev.battlecode.competition;

import dev.battlecode.engine.GameState;
import dev.battlecode.rulesapi.RuleState;
import dev.battlecode.rulesapi.bot.BotObservation;
import java.util.*;
import java.util.function.BiFunction;

/** Internal composition capability. Never exposed by the TCP/WebSocket dispatcher. */
public interface BotMatchAccess {
    record Lease(UUID matchId, UUID playerId, int expectedTurn, long generation, UUID token, BotObservation observation) { }
    UUID startBotMatch(UUID host, UUID roomId);
    Optional<Lease> captureBotTurn(UUID matchId, BiFunction<RuleState,UUID,BotObservation> trustedProjector);
    Optional<GameState> commitBotTurn(Lease lease, Map<String,String> action);
    Optional<GameState> forfeitBotTurn(Lease lease);
    boolean cancelBotMatch(UUID matchId, String reason);
}
