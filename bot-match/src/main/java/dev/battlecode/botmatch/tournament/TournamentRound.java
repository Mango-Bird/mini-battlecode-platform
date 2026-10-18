package dev.battlecode.botmatch.tournament;
import java.util.*;
public record TournamentRound(int number,List<TournamentMatch> matches,List<UUID> winners) {
    public TournamentRound {matches=List.copyOf(matches);winners=List.copyOf(winners);}
}
