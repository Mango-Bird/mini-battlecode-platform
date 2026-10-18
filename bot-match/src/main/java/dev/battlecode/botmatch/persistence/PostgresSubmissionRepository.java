package dev.battlecode.botmatch.persistence;
import dev.battlecode.botmatch.*;
import dev.battlecode.botrunner.BotSubmission;
import java.sql.*;
import java.time.*;
import java.util.*;
import static dev.battlecode.botmatch.LocalSubmissionStore.*;
/** DB is authoritative; no source or ownership cache. All writes serialize against a DB advisory lock. */
public final class PostgresSubmissionRepository implements SubmissionRepository {
    private final Database db;private final IdempotencyRepository memo=new IdempotencyRepository();
    public PostgresSubmissionRepository(Database db){this.db=db;}
    private <T>T transaction(Database.Work<T> work){db.checkLease();return db.transaction(work);}
    public UUID ownerOf(UUID id){return transaction(c->{try(var p=c.prepareStatement("SELECT owner_id FROM bots WHERE id=?")){p.setObject(1,id);try(var r=p.executeQuery()){return r.next()?r.getObject(1,UUID.class):null;}}});}
    private void authorize(Connection c,UUID owner,UUID id)throws SQLException{try(var p=c.prepareStatement("SELECT owner_id FROM bots WHERE id=? FOR UPDATE")){p.setObject(1,id);try(var r=p.executeQuery()){if(!r.next()||!Objects.equals(owner,r.getObject(1,UUID.class)))throw new SecurityException("Not owner");}}}
    public List<Detail> snapshots(){return transaction(c->{c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);var out=new ArrayList<Detail>();try(var s=c.createStatement();var r=s.executeQuery("SELECT id FROM bots WHERE deleted_at IS NULL ORDER BY created_at,id")){while(r.next())out.add(detail(c,r.getObject(1,UUID.class),false));}return List.copyOf(out);});}
    public Detail get(UUID id){return transaction(c->detail(c,id,false));}
    private Detail detail(Connection c,UUID id,boolean tombstone)throws Exception{
        String name,created,updated;int latest;
        try(var p=c.prepareStatement("SELECT name,created_at,updated_at,latest_version FROM bots WHERE id=?"+(tombstone?"":" AND deleted_at IS NULL"))){p.setObject(1,id);try(var r=p.executeQuery()){if(!r.next())throw new NoSuchElementException("Bot");name=r.getString(1);created=r.getObject(2,OffsetDateTime.class).toInstant().toString();updated=r.getObject(3,OffsetDateTime.class).toInstant().toString();latest=r.getInt(4);}}
        var versions=new ArrayList<Version>();try(var p=c.prepareStatement("SELECT version,source,source_sha256,created_at,validation_status FROM bot_versions WHERE bot_id=? ORDER BY version")){p.setObject(1,id);try(var r=p.executeQuery()){while(r.next()){byte[] bytes=r.getBytes(2);String hash=r.getString(3);var frozen=new BotSubmission(id,r.getInt(1),bytes,hash);versions.add(new Version(frozen.version(),frozen.sourceText(),hash,r.getObject(4,OffsetDateTime.class).toInstant().toString(),r.getString(5),"NOT_EXECUTABLE"));}}}
        var v=versions.getLast();return new Detail(new Metadata(id,name,created,updated,latest,v.sourceSha256(),v.validationStatus(),"NOT_EXECUTABLE"),versions);
    }
    public BotSubmission freezeVersion(UUID id,int version,String hash){return transaction(c->{try(var p=c.prepareStatement("SELECT source,source_sha256 FROM bot_versions WHERE bot_id=? AND version=?")){p.setObject(1,id);p.setInt(2,version);try(var r=p.executeQuery()){if(!r.next())throw new NoSuchElementException("Version");if(!r.getString(2).equals(hash))throw new IllegalArgumentException("Source hash mismatch");return new BotSubmission(id,version,r.getBytes(1),hash);}}});}
    public Detail createOwned(UUID owner,UUID request,String name,String source){Objects.requireNonNull(owner);var valid=new LocalSubmissionStore().create(UUID.randomUUID(),name,source);
        String fingerprint="CREATE:"+name+":"+valid.metadata().sourceSha256(),scope="bot:"+owner;
        return transaction(c->{Database.lock(c,"bot-mutations");Detail old=memo.previous(c,scope,request,fingerprint,Detail.class);if(old!=null)return old;
            try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM bots")){r.next();if(r.getInt(1)>=MAX_BOTS)throw new IllegalStateException("Durable bot capacity");}
            var m=valid.metadata();try(var p=c.prepareStatement("INSERT INTO bots(id,owner_id,name,created_at,updated_at,latest_version) VALUES (?,?,?,?,?,1)")){p.setObject(1,m.botId());p.setObject(2,owner);p.setString(3,name);p.setObject(4,OffsetDateTime.parse(m.createdAt()));p.setObject(5,OffsetDateTime.parse(m.updatedAt()));p.executeUpdate();}
            insertVersion(c,m.botId(),valid.versions().getFirst());memo.remember(c,scope,request,fingerprint,valid);return valid;
        });
    }
    private static void insertVersion(Connection c,UUID id,Version v)throws SQLException{try(var p=c.prepareStatement("INSERT INTO bot_versions(bot_id,version,source,source_sha256,created_at,validation_status) VALUES (?,?,?,?,?,?)")){p.setObject(1,id);p.setInt(2,v.version());p.setBytes(3,v.source().getBytes(java.nio.charset.StandardCharsets.UTF_8));p.setString(4,v.sourceSha256());p.setObject(5,OffsetDateTime.parse(v.createdAt()));p.setString(6,v.validationStatus());p.executeUpdate();}}
    private static void expected(Detail d,int expected){if(d.metadata().latestVersion()!=expected)throw new IllegalStateException("Stale expectedVersion");}
    public Detail saveOwned(UUID owner,UUID request,UUID id,int expected,String name,String source){var valid=new LocalSubmissionStore().create(UUID.randomUUID(),name,source);String fingerprint="SAVE:"+id+":"+expected+":"+name+":"+valid.metadata().sourceSha256(),scope="bot:"+owner;
        return transaction(c->{Database.lock(c,"bot-mutations");authorize(c,owner,id);Detail old=memo.previous(c,scope,request,fingerprint,Detail.class);if(old!=null)return old;
            var before=detail(c,id,false);expected(before,expected);if(expected>=MAX_VERSIONS)throw new IllegalStateException("Version capacity");
            var v=valid.versions().getFirst();v=new Version(expected+1,v.source(),v.sourceSha256(),Instant.now().toString(),v.validationStatus(),v.executionEligibility());insertVersion(c,id,v);
            try(var p=c.prepareStatement("UPDATE bots SET name=?,latest_version=?,updated_at=? WHERE id=? AND latest_version=? AND deleted_at IS NULL")){p.setString(1,name);p.setInt(2,expected+1);p.setObject(3,OffsetDateTime.parse(v.createdAt()));p.setObject(4,id);p.setInt(5,expected);if(p.executeUpdate()!=1)throw new IllegalStateException("Concurrent save");}
            var response=detail(c,id,false);memo.remember(c,scope,request,fingerprint,response);return response;
        });
    }
    public Map<String,Object> deleteOwned(UUID owner,UUID request,UUID id,int expected){String scope="bot:"+owner,fingerprint="DELETE:"+id+":"+expected;
        return transaction(c->{Database.lock(c,"bot-mutations");authorize(c,owner,id);@SuppressWarnings("unchecked")Map<String,Object> old=memo.previous(c,scope,request,fingerprint,Map.class);if(old!=null)return old;expected(detail(c,id,false),expected);
            try(var p=c.prepareStatement("UPDATE bots SET deleted_at=now(),updated_at=now() WHERE id=?")){p.setObject(1,id);p.executeUpdate();}var response=Map.<String,Object>of("deleted",id);memo.remember(c,scope,request,fingerprint,response);return response;
        });
    }
    public Validation validateOwned(UUID owner,UUID request,UUID id,int expected){String scope="bot:"+owner,fingerprint="VALIDATE:"+id+":"+expected;
        return transaction(c->{Database.lock(c,"bot-mutations");authorize(c,owner,id);Validation old=memo.previous(c,scope,request,fingerprint,Validation.class);if(old!=null)return old;
            var d=detail(c,id,false);expected(d,expected);var v=d.versions().getLast();var response=new Validation(id,expected,v.sourceSha256(),"SOURCE_ACCEPTED","NOT_PERFORMED","NOT_VERIFIED","NOT_EXECUTABLE","Source/metadata accepted; execution requires live verified sandbox; no execution performed");memo.remember(c,scope,request,fingerprint,response);return response;
        });
    }
}
