package dev.battlecode.botmatch.tournament;
import java.time.Instant;
import java.util.*;
public record Tournament(UUID tournamentId,TournamentConfig config,Instant createdAt,UUID ownerId,TournamentStatus status,
    List<TournamentEntry> entries,List<TournamentRound> rounds,List<TournamentStanding> standings,TournamentResult result,String schedulingReason) {
    public Tournament {entries=List.copyOf(entries);rounds=List.copyOf(rounds);standings=List.copyOf(standings);}
}
