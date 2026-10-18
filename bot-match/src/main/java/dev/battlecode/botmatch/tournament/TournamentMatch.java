package dev.battlecode.botmatch.tournament;
import java.util.UUID;
import dev.battlecode.botmatch.LocalArenaService;
public record TournamentMatch(UUID matchId,int round,int slot,int attempt,UUID entryA,UUID entryB,String status,UUID battleId,UUID winnerId,LocalArenaService.Result result,String interruptionReason) {
    public TournamentMatch(UUID matchId,int round,int slot,int attempt,UUID entryA,UUID entryB,String status,UUID battleId,UUID winnerId,LocalArenaService.Result result) {
        this(matchId,round,slot,attempt,entryA,entryB,status,battleId,winnerId,result,status.equals("CANCELLED")&&result!=null?result.reason():"");
    }
}
