package dev.battlecode.botmatch.ranked;
import java.time.Instant;
import java.util.UUID;
public record MatchmakingTicket(UUID ticketId,UUID ownerId,UUID botId,int version,String sourceSha256,String gameMode,String rulesVersion,double elo,Instant queuedAt,String status){}
