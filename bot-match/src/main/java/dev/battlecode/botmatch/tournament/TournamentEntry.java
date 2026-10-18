package dev.battlecode.botmatch.tournament;
import java.util.UUID;
/** Public metadata only. Frozen source belongs exclusively to the service's private storage. */
public record TournamentEntry(UUID entryId,UUID ownerId,String ownerDisplay,UUID botId,String botName,int version,String sourceSha256,int seedOrder) {}
