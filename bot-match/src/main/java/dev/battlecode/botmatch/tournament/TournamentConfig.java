package dev.battlecode.botmatch.tournament;
import java.time.Instant;
public record TournamentConfig(String name,TournamentFormat format,int maxParticipants,Instant registrationDeadline,long seed,int maxDrawRematches) {
    public TournamentConfig {
        if(name==null||name.isBlank()||name.length()>80||name.chars().anyMatch(c->c<32)||format==null
            ||maxParticipants<2||maxParticipants>16||maxDrawRematches<0||maxDrawRematches>2)throw new IllegalArgumentException("Tournament config");
    }
}
