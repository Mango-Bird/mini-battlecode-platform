package dev.battlecode.botmatch.tournament;
import java.util.UUID;
public record TournamentStanding(int rank,UUID entryId,String botName,String ownerDisplay,int played,int wins,int draws,int losses,int forfeits,int points) {}
