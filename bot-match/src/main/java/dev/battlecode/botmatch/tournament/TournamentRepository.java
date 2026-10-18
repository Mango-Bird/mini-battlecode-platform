package dev.battlecode.botmatch.tournament;
import java.util.*;
import dev.battlecode.botmatch.LocalArenaService;
/** Durable checkpoint contract. Sources are resolved through immutable bot-version references, never public snapshots. */
public interface TournamentRepository {
    record Restored(Tournament tournament,Map<UUID,LocalArenaService.Snapshot> replay){public Restored{replay=Map.copyOf(replay);}}
    record Mutation(UUID owner,UUID request,String fingerprint,Tournament response){}
    List<Restored> load();
    Tournament previous(UUID owner,UUID request,String fingerprint);
    void save(List<Restored> state,Mutation mutation);
    void checkAvailable();
}
