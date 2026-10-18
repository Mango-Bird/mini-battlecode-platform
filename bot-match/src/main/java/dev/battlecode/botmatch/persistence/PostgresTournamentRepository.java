package dev.battlecode.botmatch.persistence;
import dev.battlecode.botmatch.tournament.*;
import dev.battlecode.botmatch.LocalArenaService;
import dev.battlecode.botmatch.rating.*;
import java.util.*;
import java.sql.*;
import java.time.*;
import com.fasterxml.jackson.databind.ObjectMapper;
/** Single lease owner writes transactional domain checkpoints plus normalized query/replay tables. */
public final class PostgresTournamentRepository implements TournamentRepository {
    private final Database db;private final ObjectMapper json=PersistenceJson.mapper();private final IdempotencyRepository memo=new IdempotencyRepository();
    private final RatingService ratings;
    public PostgresTournamentRepository(Database db){this.db=db;this.ratings=new RatingService(db);}
    public void checkAvailable(){db.checkLease();}
    public List<Restored> load(){checkAvailable();return db.transaction(c->{var state=new ArrayList<Restored>();try(var s=c.createStatement();var r=s.executeQuery("SELECT id,domain_snapshot::text FROM tournaments ORDER BY created_at,id")){while(r.next()){
        var t=json.readValue(r.getString(2),Tournament.class);var replay=new HashMap<UUID,LocalArenaService.Snapshot>();
        try(var p=c.prepareStatement("SELECT id,replay_snapshot::text FROM tournament_matches WHERE tournament_id=? AND replay_snapshot IS NOT NULL")){p.setObject(1,t.tournamentId());try(var events=p.executeQuery()){while(events.next())replay.put(events.getObject(1,UUID.class),json.readValue(events.getString(2),LocalArenaService.Snapshot.class));}}
        state.add(new Restored(t,replay));
    }}return List.copyOf(state);});}
    public Tournament previous(UUID owner,UUID request,String fingerprint){checkAvailable();return db.transaction(c->memo.previous(c,"tournament:"+owner,request,fingerprint,Tournament.class));}
    public void save(List<Restored> state,Mutation mutation){checkAvailable();db.transaction(c->{Database.lock(c,"tournament-checkpoint");
        for(var item:state){var t=item.tournament();var config=t.config();
            try(var p=c.prepareStatement("INSERT INTO tournaments(id,owner_id,name,format,status,seed,max_participants,max_draw_rematches,registration_deadline,created_at,domain_snapshot) VALUES (?,?,?,?,?,?,?,?,?,?,?::jsonb) ON CONFLICT(id) DO UPDATE SET status=EXCLUDED.status,updated_at=now(),domain_snapshot=EXCLUDED.domain_snapshot")){
                p.setObject(1,t.tournamentId());p.setObject(2,t.ownerId());p.setString(3,config.name());p.setString(4,config.format().name());p.setString(5,t.status().name());p.setLong(6,config.seed());p.setInt(7,config.maxParticipants());p.setInt(8,config.maxDrawRematches());p.setObject(9,config.registrationDeadline()==null?null:OffsetDateTime.ofInstant(config.registrationDeadline(),ZoneOffset.UTC));p.setObject(10,OffsetDateTime.ofInstant(t.createdAt(),ZoneOffset.UTC));p.setString(11,json.writeValueAsString(t));p.executeUpdate();
            }
            for(var entry:t.entries())try(var p=c.prepareStatement("INSERT INTO tournament_entries(id,tournament_id,owner_id,bot_id,bot_version,source_sha256,seed_order) VALUES (?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET seed_order=EXCLUDED.seed_order")){p.setObject(1,entry.entryId());p.setObject(2,t.tournamentId());p.setObject(3,entry.ownerId());p.setObject(4,entry.botId());p.setInt(5,entry.version());p.setString(6,entry.sourceSha256());p.setInt(7,entry.seedOrder());p.executeUpdate();}
            // Withdrawals before scheduling have no match references.
            try(var p=c.prepareStatement("DELETE FROM tournament_entries WHERE tournament_id=? AND NOT (id = ANY (?))")){p.setObject(1,t.tournamentId());p.setArray(2,c.createArrayOf("uuid",t.entries().stream().map(TournamentEntry::entryId).toArray()));p.executeUpdate();}
            for(var round:t.rounds())for(var match:round.matches()){
                var replay=item.replay().get(match.matchId());String reason=match.result()==null?match.interruptionReason():match.result().reason();
                try(var p=c.prepareStatement("INSERT INTO tournament_matches(id,tournament_id,round_number,slot,attempt,bot_a_entry_id,bot_b_entry_id,status,winner_entry_id,result_reason,started_at,finished_at,replay_snapshot) VALUES (?,?,?,?,?,?,?,?,?,?,CASE WHEN ? THEN now() ELSE NULL END,CASE WHEN ? THEN now() ELSE NULL END,?::jsonb) ON CONFLICT(id) DO UPDATE SET status=EXCLUDED.status,winner_entry_id=EXCLUDED.winner_entry_id,result_reason=EXCLUDED.result_reason,started_at=coalesce(tournament_matches.started_at,EXCLUDED.started_at),finished_at=coalesce(tournament_matches.finished_at,EXCLUDED.finished_at),replay_snapshot=coalesce(tournament_matches.replay_snapshot,EXCLUDED.replay_snapshot)")){
                    p.setObject(1,match.matchId());p.setObject(2,t.tournamentId());p.setInt(3,match.round());p.setInt(4,match.slot());p.setInt(5,match.attempt());p.setObject(6,match.entryA());p.setObject(7,match.entryB());p.setString(8,match.status());p.setObject(9,match.winnerId());p.setString(10,reason);p.setBoolean(11,match.battleId()!=null||match.status().equals("RUNNING"));p.setBoolean(12,Set.of("FINISHED","DRAW_REPLAY","CANCELLED","BYE").contains(match.status()));p.setString(13,replay==null?null:json.writeValueAsString(replay));p.executeUpdate();
                }
                if(match.entryB()!=null&&match.result()!=null&&Set.of("FINISHED","DRAW_REPLAY","CANCELLED").contains(match.status())){
                    var a=t.entries().stream().filter(e->e.entryId().equals(match.entryA())).findFirst().orElseThrow();
                    var b=t.entries().stream().filter(e->e.entryId().equals(match.entryB())).findFirst().orElseThrow();
                    Instant completed;boolean activated;
                    try(var p=c.prepareStatement("SELECT m.finished_at,m.finished_at>=a.activated_at FROM tournament_matches m CROSS JOIN rating_policy_activation a WHERE m.id=? AND a.version='rating-v1'")){
                        p.setObject(1,match.matchId());try(var r=p.executeQuery()){r.next();completed=r.getTimestamp(1).toInstant();activated=r.getBoolean(2);}}
                    String outcome=match.status().equals("CANCELLED")||match.result().outcome().equals("CANCELLED")?"CANCELLED":match.result().outcome().equals("DRAW")?"DRAW":match.result().winner();
                    if(!Set.of("A","B","DRAW","CANCELLED").contains(outcome))throw new IllegalStateException("Authoritative result shape");
                    ratings.confirm(c,new ConfirmedMatchResult(match.matchId(),"ott-board-9x9-v1","ott-board-9x9-v1",
                        new ConfirmedMatchResult.Bot(a.botId(),a.ownerId(),a.version(),a.sourceSha256()),new ConfirmedMatchResult.Bot(b.botId(),b.ownerId(),b.version(),b.sourceSha256()),
                        outcome,match.result().reason(),activated,completed));
                }
                if(replay!=null)for(int sequence=0;sequence<replay.frames().size();sequence++){
                    Object event=sequence==0?Map.of("board",replay.frames().getFirst()):Map.of("move",replay.moves().get(sequence-1),"board",replay.frames().get(sequence));
                    try(var p=c.prepareStatement("INSERT INTO match_events(match_id,sequence_number,event_payload) VALUES (?,?,?::jsonb) ON CONFLICT DO NOTHING")){p.setObject(1,match.matchId());p.setInt(2,sequence);p.setString(3,json.writeValueAsString(event));p.executeUpdate();}
                }
            }
        }
        if(mutation!=null)memo.remember(c,"tournament:"+mutation.owner(),mutation.request(),mutation.fingerprint(),mutation.response());return null;
    });}
}
