package dev.battlecode.botmatch.tournament;
import java.util.*;
public record TournamentResult(UUID championId,String reason,List<TournamentStanding> standings) {
    public TournamentResult {standings=List.copyOf(standings);}
}
