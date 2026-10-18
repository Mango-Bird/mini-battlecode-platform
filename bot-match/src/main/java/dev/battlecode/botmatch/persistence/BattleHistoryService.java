package dev.battlecode.botmatch.persistence;
import dev.battlecode.botmatch.LocalArenaService;
import java.util.*;
import java.util.function.Supplier;
import com.fasterxml.jackson.databind.ObjectMapper;
/** Persistent admission intents and public snapshots for existing standalone Arena matches. */
public final class BattleHistoryService {
    private final Database db;private final LocalArenaService arena;private final ObjectMapper json=PersistenceJson.mapper();
    private volatile boolean failed;
    public BattleHistoryService(Database db,LocalArenaService arena){this.db=db;this.arena=arena;
        db.transaction(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT request_id,snapshot::text FROM arena_battles WHERE status IN ('PENDING','RUNNING') FOR UPDATE")){
            while(r.next()){
                var snapshot=r.getString(2)==null?null:json.readValue(r.getString(2),LocalArenaService.Snapshot.class);
                if(snapshot!=null)snapshot=new LocalArenaService.Snapshot(snapshot.id(),snapshot.botA(),snapshot.botB(),"CANCELLED",snapshot.turn(),snapshot.currentSide(),snapshot.board(),snapshot.moves(),snapshot.frames(),new LocalArenaService.Result("CANCELLED",null,"BACKEND_RESTART_INTERRUPTED"));
                try(var p=c.prepareStatement("UPDATE arena_battles SET status='CANCELLED',snapshot=?::jsonb,updated_at=now() WHERE request_id=?")){p.setString(1,snapshot==null?null:json.writeValueAsString(snapshot));p.setObject(2,r.getObject(1,UUID.class));p.executeUpdate();}
            }
        }return null;});
    }
    private void available(){if(failed)throw new Database.Unavailable();db.checkLease();}
    public LocalArenaService.Snapshot start(UUID owner,UUID request,String fingerprint,Supplier<LocalArenaService.Snapshot> execute){available();
        var old=db.transaction(c->{Database.lock(c,"manual-arena-admission");
            try(var p=c.prepareStatement("SELECT owner_id,payload_hash,status,snapshot::text FROM arena_battles WHERE request_id=?")){p.setObject(1,request);try(var r=p.executeQuery()){if(r.next()){
                if(!owner.equals(r.getObject(1,UUID.class))||!r.getString(2).equals(IdempotencyRepository.hash(fingerprint)))throw new IllegalStateException("Request ID conflict");
                if(r.getString(4)==null)throw new IllegalStateException("Attempt interrupted or rejected; no automatic execution retry");return json.readValue(r.getString(4),LocalArenaService.Snapshot.class);
            }}}
            try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*),count(*) FILTER (WHERE status IN ('PENDING','RUNNING')) FROM arena_battles")){r.next();if(r.getInt(1)>=16||r.getInt(2)>0)throw new IllegalStateException("Arena history or admission capacity");}
            try(var p=c.prepareStatement("INSERT INTO arena_battles(request_id,owner_id,payload_hash,status) VALUES (?,?,?,'PENDING')")){p.setObject(1,request);p.setObject(2,owner);p.setString(3,IdempotencyRepository.hash(fingerprint));p.executeUpdate();}return null;
        });
        if(old!=null)return old;
        try{var snapshot=execute.get();record(request,snapshot);return get(snapshot.id());}
        catch(RuntimeException e){if(!failed)db.transaction(c->{try(var p=c.prepareStatement("UPDATE arena_battles SET status='REJECTED',updated_at=now() WHERE request_id=? AND status='PENDING'")){p.setObject(1,request);p.executeUpdate();}return null;});throw e;}
    }
    public void record(UUID request,LocalArenaService.Snapshot snapshot){if(snapshot.id()==null)return;try{available();db.transaction(c->{
        try(var p=c.prepareStatement("UPDATE arena_battles SET battle_id=?,status=?,snapshot=?::jsonb,updated_at=now() WHERE request_id=? AND status IN ('PENDING','RUNNING')")){p.setObject(1,snapshot.id());p.setString(2,snapshot.status());p.setString(3,json.writeValueAsString(snapshot));p.setObject(4,request);p.executeUpdate();}return null;
    });}catch(RuntimeException e){failed=true;arena.cancelBattle(snapshot.id());throw e;}}
    public LocalArenaService.Snapshot get(UUID id){available();return db.transaction(c->{try(var p=c.prepareStatement("SELECT snapshot::text FROM arena_battles WHERE battle_id=?")){p.setObject(1,id);try(var r=p.executeQuery()){if(!r.next())throw new NoSuchElementException("Battle");return json.readValue(r.getString(1),LocalArenaService.Snapshot.class);}}});}
    public record Metadata(UUID id,String botA,String botB,String status,int moves,LocalArenaService.Result result){}
    public LocalArenaService.Snapshot getOwned(UUID owner,UUID id){available();return db.transaction(c->{try(var p=c.prepareStatement("SELECT snapshot::text FROM arena_battles WHERE battle_id=? AND owner_id=?")){p.setObject(1,id);p.setObject(2,owner);try(var r=p.executeQuery()){if(!r.next())throw new NoSuchElementException("Battle");return json.readValue(r.getString(1),LocalArenaService.Snapshot.class);}}});}
    public List<Metadata> listOwned(UUID owner){available();return db.transaction(c->{var out=new ArrayList<Metadata>();try(var p=c.prepareStatement("SELECT snapshot::text FROM arena_battles WHERE owner_id=? AND snapshot IS NOT NULL ORDER BY created_at DESC LIMIT 100")){p.setObject(1,owner);try(var r=p.executeQuery()){while(r.next()){var b=json.readValue(r.getString(1),LocalArenaService.Snapshot.class);out.add(new Metadata(b.id(),b.botA(),b.botB(),b.status(),b.moves().size(),b.result()));}}}return List.copyOf(out);});}
    public List<Metadata> list(){available();return db.transaction(c->{var out=new ArrayList<Metadata>();try(var s=c.createStatement();var r=s.executeQuery("SELECT snapshot::text FROM arena_battles WHERE snapshot IS NOT NULL ORDER BY created_at DESC")){while(r.next()){var b=json.readValue(r.getString(1),LocalArenaService.Snapshot.class);out.add(new Metadata(b.id(),b.botA(),b.botB(),b.status(),b.moves().size(),b.result()));}}return List.copyOf(out);});}
}
