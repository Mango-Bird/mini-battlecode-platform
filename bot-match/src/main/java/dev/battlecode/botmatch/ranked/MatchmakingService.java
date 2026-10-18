package dev.battlecode.botmatch.ranked;
import dev.battlecode.botmatch.*;
import dev.battlecode.botmatch.persistence.*;
import dev.battlecode.botmatch.rating.*;
import java.util.*;
import java.sql.*;
import java.time.*;
import java.util.function.BooleanSupplier;

/** Transactions only; execution never occurs under a database lock. */
public final class MatchmakingService {
    public static final String MODE="ott-board-9x9-v1";
    private final Database db;private final SubmissionRepository sources;private final BooleanSupplier gate;
    private final MatchmakingPolicy policy;private final Clock clock;
    private final IdempotencyRepository memo=new IdempotencyRepository();
    public MatchmakingService(Database db,SubmissionRepository sources,BooleanSupplier gate){this(db,sources,gate,MatchmakingPolicy.V1,Clock.systemUTC());}
    public MatchmakingService(Database db,SubmissionRepository sources,BooleanSupplier gate,MatchmakingPolicy policy,Clock clock){this.db=db;this.sources=sources;this.gate=gate;this.policy=policy;this.clock=clock;}
    private Instant now(){return clock.instant();}
    public boolean hasWaitingPair(){db.checkLease();return db.transaction(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM ranked_queue_tickets WHERE status='WAITING'")){r.next();return r.getInt(1)>=2;}});}
    public MatchmakingTicket join(UUID owner,UUID request,UUID bot,int version,String hash){
        db.checkLease();
        if(!gate.getAsBoolean())throw new IllegalStateException("Verified worker unavailable");
        if(!owner.equals(sources.ownerOf(bot)))throw new SecurityException("Bot owner");sources.freezeVersion(bot,version,hash);
        return db.transaction(c->{Database.lock(c,"ranked-queue");String scope="ranked:"+owner,fp="JOIN:"+bot+":"+version+":"+hash;
            var old=memo.previous(c,scope,request,fp,MatchmakingTicket.class);if(old!=null)return old;
            rate(c,owner);expire(c);authenticated(c,owner);
            if(!eligibleBot(c,owner,bot,version,hash))throw new SecurityException("Frozen bot unavailable");
            try(var p=c.prepareStatement("SELECT 1 FROM ranked_queue_tickets WHERE (owner_id=? OR bot_id=?) AND status IN ('WAITING','RESERVED','RUNNING')")){p.setObject(1,owner);p.setObject(2,bot);try(var r=p.executeQuery()){if(r.next())throw new IllegalStateException("Already queued or running");}}
            try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FILTER(WHERE status IN ('WAITING','RESERVED','RUNNING')),count(*) FROM ranked_queue_tickets")){r.next();if(r.getInt(1)>=policy.capacity()||r.getInt(2)>=4096)throw new IllegalStateException("Queue capacity");}
            double elo=1500;try(var p=c.prepareStatement("SELECT elo FROM rating_profiles WHERE bot_id=? AND game_mode=? AND rules_version=?")){p.setObject(1,bot);p.setString(2,MODE);p.setString(3,MODE);try(var r=p.executeQuery()){if(r.next())elo=r.getDouble(1);}}
            var ticket=new MatchmakingTicket(UUID.randomUUID(),owner,bot,version,hash,MODE,MODE,elo,now(),"WAITING");
            try(var p=c.prepareStatement("INSERT INTO ranked_queue_tickets(ticket_id,owner_id,bot_id,bot_version,source_sha256,game_mode,rules_version,elo,queued_at,status) VALUES (?,?,?,?,?,?,?,?,?,?)")){
                p.setObject(1,ticket.ticketId());p.setObject(2,owner);p.setObject(3,bot);p.setInt(4,version);p.setString(5,hash);p.setString(6,MODE);p.setString(7,MODE);p.setDouble(8,elo);p.setObject(9,OffsetDateTime.ofInstant(now(),ZoneOffset.UTC));p.setString(10,"WAITING");p.executeUpdate();}
            audit(c,owner,null,"JOIN");memo.remember(c,scope,request,fp,ticket);return ticket;});
    }
    private void authenticated(Connection c,UUID owner)throws SQLException{try(var p=c.prepareStatement("SELECT 1 FROM user_sessions WHERE user_id=? AND revoked_at IS NULL AND expires_at>? LIMIT 1")){p.setObject(1,owner);p.setObject(2,OffsetDateTime.ofInstant(now(),ZoneOffset.UTC));try(var r=p.executeQuery()){if(!r.next())throw new SecurityException("Live authentication required");}}}
    private boolean eligibleBot(Connection c,UUID owner,UUID bot,int version,String hash)throws SQLException{try(var p=c.prepareStatement("SELECT 1 FROM bots b JOIN bot_versions v ON v.bot_id=b.id WHERE b.id=? AND b.owner_id=? AND b.deleted_at IS NULL AND v.version=? AND v.source_sha256=?")){p.setObject(1,bot);p.setObject(2,owner);p.setInt(3,version);p.setString(4,hash);try(var r=p.executeQuery()){return r.next();}}}
    private void rate(Connection c,UUID owner)throws SQLException{try(var p=c.prepareStatement("SELECT count(*) FROM matchmaking_audit_events WHERE owner_id=? AND kind IN ('JOIN','CANCEL') AND created_at>?")){p.setObject(1,owner);p.setObject(2,OffsetDateTime.ofInstant(now().minusSeconds(policy.rateSeconds()),ZoneOffset.UTC));try(var r=p.executeQuery()){r.next();if(r.getInt(1)>=policy.rateCount())throw new IllegalStateException("Queue rate limit");}}}
    private static MatchmakingTicket ticket(ResultSet r)throws SQLException{return new MatchmakingTicket(r.getObject("ticket_id",UUID.class),r.getObject("owner_id",UUID.class),r.getObject("bot_id",UUID.class),r.getInt("bot_version"),r.getString("source_sha256").trim(),r.getString("game_mode"),r.getString("rules_version"),r.getDouble("elo"),r.getTimestamp("queued_at").toInstant(),r.getString("status"));}
    private void expire(Connection c)throws SQLException{try(var p=c.prepareStatement("UPDATE ranked_queue_tickets SET status='EXPIRED',updated_at=now() WHERE status='WAITING' AND queued_at<?")){p.setObject(1,OffsetDateTime.ofInstant(now().minusSeconds(policy.lifetimeSeconds()),ZoneOffset.UTC));p.executeUpdate();}}
    public RankedQueueEntry me(UUID owner){db.checkLease();return db.transaction(c->{Database.lock(c,"ranked-queue");expire(c);try(var p=c.prepareStatement("SELECT * FROM ranked_queue_tickets WHERE owner_id=? ORDER BY queued_at DESC,ticket_id DESC LIMIT 1")){p.setObject(1,owner);try(var r=p.executeQuery()){if(!r.next())return null;var t=ticket(r);return new RankedQueueEntry(t,policy.window(Duration.between(t.queuedAt(),now())),Math.max(0,Duration.between(t.queuedAt(),now()).getSeconds()));}}});}
    public MatchmakingTicket cancel(UUID owner,UUID request){db.checkLease();return db.transaction(c->{Database.lock(c,"ranked-queue");String scope="ranked:"+owner;var old=memo.previous(c,scope,request,"CANCEL",MatchmakingTicket.class);if(old!=null)return old;
        rate(c,owner);try(var p=c.prepareStatement("UPDATE ranked_queue_tickets SET status='CANCELLED',updated_at=now() WHERE owner_id=? AND status='WAITING' RETURNING *")){p.setObject(1,owner);try(var r=p.executeQuery()){if(!r.next())throw new IllegalStateException("Search already reserved or ended");var t=ticket(r);audit(c,owner,null,"CANCEL");memo.remember(c,scope,request,"CANCEL",t);return t;}}});}
    public RankedPairing reserve(){db.checkLease();if(!gate.getAsBoolean())return null;return db.transaction(c->{Database.lock(c,"ranked-queue");expire(c);
        try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM ranked_pairings WHERE status IN ('RESERVED','RUNNING')")){r.next();if(r.getInt(1)>0)return null;}
        var waiting=new ArrayList<MatchmakingTicket>();try(var s=c.createStatement();var r=s.executeQuery("SELECT * FROM ranked_queue_tickets WHERE status='WAITING' ORDER BY queued_at,ticket_id FOR UPDATE")){while(r.next())waiting.add(ticket(r));}
        for(var a:waiting){
            var compatible=waiting.stream().filter(b->!a.ownerId().equals(b.ownerId())&&!a.botId().equals(b.botId())&&a.gameMode().equals(b.gameMode())&&a.rulesVersion().equals(b.rulesVersion())
                &&Math.abs(a.elo()-b.elo())<=Math.min(policy.window(Duration.between(a.queuedAt(),now())),policy.window(Duration.between(b.queuedAt(),now()))))
                .sorted(Comparator.comparingDouble((MatchmakingTicket b)->Math.abs(a.elo()-b.elo())).thenComparing(MatchmakingTicket::queuedAt).thenComparing(t->t.ticketId().toString())).toList();
            for(var b:compatible){
                if(repeated(c,a,b))continue;
                if(!eligibleBot(c,a.ownerId(),a.botId(),a.version(),a.sourceSha256())||!eligibleBot(c,b.ownerId(),b.botId(),b.version(),b.sourceSha256()))continue;
                try{authenticated(c,a.ownerId());authenticated(c,b.ownerId());if(!a.ownerId().equals(sources.ownerOf(a.botId()))||!b.ownerId().equals(sources.ownerOf(b.botId())))continue;sources.freezeVersion(a.botId(),a.version(),a.sourceSha256());sources.freezeVersion(b.botId(),b.version(),b.sourceSha256());}catch(SecurityException|NoSuchElementException e){continue;}
                UUID id=UUID.randomUUID();var first=a.ticketId().toString().compareTo(b.ticketId().toString())<0?a:b;var second=first==a?b:a;
                try(var p=c.prepareStatement("UPDATE ranked_queue_tickets SET status='RESERVED',updated_at=now() WHERE ticket_id IN (?,?) AND status='WAITING'")){p.setObject(1,a.ticketId());p.setObject(2,b.ticketId());if(p.executeUpdate()!=2)throw new IllegalStateException("Reservation race");}
                try(var p=c.prepareStatement("INSERT INTO ranked_pairings(pairing_id,ticket_a,ticket_b,policy_version,status) VALUES (?,?,?,?,'RESERVED')")){p.setObject(1,id);p.setObject(2,first.ticketId());p.setObject(3,second.ticketId());p.setString(4,policy.version());p.executeUpdate();}
                for(int n=1;n<=2;n++)try(var p=c.prepareStatement("INSERT INTO ranked_games(match_id,pairing_id,game_number,status) VALUES (?,?,?,'RESERVED')")){p.setObject(1,gameId(id,n));p.setObject(2,id);p.setInt(3,n);p.executeUpdate();}
                audit(c,a.ownerId(),id,"RESERVED");audit(c,b.ownerId(),id,"RESERVED");return new RankedPairing(id,first,second,policy.version());
            }
        }return null;});}
    private boolean repeated(Connection c,MatchmakingTicket a,MatchmakingTicket b)throws SQLException{try(var p=c.prepareStatement("SELECT p.pairing_id FROM ranked_pairings p JOIN ranked_queue_tickets a ON a.ticket_id=p.ticket_a JOIN ranked_queue_tickets b ON b.ticket_id=p.ticket_b WHERE p.created_at>? AND ((a.owner_id=? AND b.owner_id=?) OR (a.owner_id=? AND b.owner_id=?)) LIMIT 1")){
        p.setObject(1,OffsetDateTime.ofInstant(now().minusSeconds(policy.repeatSeconds()),ZoneOffset.UTC));p.setObject(2,a.ownerId());p.setObject(3,b.ownerId());p.setObject(4,b.ownerId());p.setObject(5,a.ownerId());try(var r=p.executeQuery()){if(!r.next())return false;UUID previous=r.getObject(1,UUID.class);
            try(var q=c.prepareStatement("INSERT INTO matchmaking_audit_events(owner_id,pairing_id,kind) SELECT ?,?,'RECENT_PAIR_AVOIDED' WHERE NOT EXISTS (SELECT 1 FROM matchmaking_audit_events WHERE owner_id=? AND pairing_id=? AND kind='RECENT_PAIR_AVOIDED')")){q.setObject(1,a.ownerId());q.setObject(2,previous);q.setObject(3,a.ownerId());q.setObject(4,previous);q.executeUpdate();}return true;}}}
    public static UUID gameId(UUID pairing,int n){return UUID.nameUUIDFromBytes((pairing+":"+n).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    private void audit(Connection c,UUID owner,UUID pairing,String kind)throws SQLException{try(var p=c.prepareStatement("INSERT INTO matchmaking_audit_events(owner_id,pairing_id,kind) VALUES (?,?,?)")){p.setObject(1,owner);p.setObject(2,pairing);p.setString(3,kind);p.executeUpdate();}}
    public void running(RankedPairing pair,int number,UUID battle){db.checkLease();db.transaction(c->{Database.lock(c,"ranked-queue");
        try(var p=c.prepareStatement("UPDATE ranked_games SET status='RUNNING',battle_id=? WHERE match_id=? AND status='RESERVED'")){p.setObject(1,battle);p.setObject(2,gameId(pair.pairingId(),number));if(p.executeUpdate()!=1)throw new IllegalStateException("Double start");}
        try(var p=c.prepareStatement("UPDATE ranked_pairings SET status='RUNNING' WHERE pairing_id=? AND status IN ('RESERVED','RUNNING')")){p.setObject(1,pair.pairingId());p.executeUpdate();}
        try(var p=c.prepareStatement("UPDATE ranked_queue_tickets SET status='RUNNING' WHERE ticket_id IN (?,?) AND status='RESERVED'")){p.setObject(1,pair.a().ticketId());p.setObject(2,pair.b().ticketId());p.executeUpdate();}return null;});}
    public void progress(UUID match,LocalArenaService.Snapshot snapshot){db.checkLease();db.transaction(c->{try(var p=c.prepareStatement("UPDATE ranked_games SET snapshot=?::jsonb WHERE match_id=? AND status='RUNNING'")){p.setString(1,PersistenceJson.mapper().writeValueAsString(snapshot));p.setObject(2,match);p.executeUpdate();}return null;});}
    public void complete(RankedPairing pair,int n,LocalArenaService.Snapshot snapshot){
        db.checkLease();if(!snapshot.status().equals("FINISHED")||snapshot.result()==null)throw new IllegalStateException("No authoritative result");
        var a=n==1?pair.a():pair.b();var b=n==1?pair.b():pair.a();
        db.transaction(c->{Database.lock(c,"ranked-queue");UUID match=gameId(pair.pairingId(),n);
            Instant completed=now();try(var p=c.prepareStatement("SELECT status FROM ranked_games WHERE match_id=? FOR UPDATE")){p.setObject(1,match);try(var r=p.executeQuery()){r.next();if(r.getString(1).equals("COMPLETED"))return null;if(!r.getString(1).equals("RUNNING"))throw new IllegalStateException("Interrupted game");}}
            String outcome=snapshot.result().outcome().equals("DRAW")?"DRAW":snapshot.result().winner();
            new RatingService(db).confirm(c,new ConfirmedMatchResult(match,MODE,MODE,new ConfirmedMatchResult.Bot(a.botId(),a.ownerId(),a.version(),a.sourceSha256()),new ConfirmedMatchResult.Bot(b.botId(),b.ownerId(),b.version(),b.sourceSha256()),outcome,snapshot.result().reason(),true,completed));
            try(var p=c.prepareStatement("UPDATE ranked_games SET status='COMPLETED',snapshot=?::jsonb,completed_at=? WHERE match_id=?")){p.setString(1,PersistenceJson.mapper().writeValueAsString(snapshot));p.setObject(2,OffsetDateTime.ofInstant(completed,ZoneOffset.UTC));p.setObject(3,match);p.executeUpdate();}
            audit(c,a.ownerId(),pair.pairingId(),"CONFIRMED_GAME");audit(c,b.ownerId(),pair.pairingId(),"CONFIRMED_GAME");
            for(UUID owner:List.of(a.ownerId(),b.ownerId()))try(var p=c.prepareStatement("SELECT count(*) FROM matchmaking_audit_events WHERE owner_id=? AND kind='CONFIRMED_GAME' AND created_at>now()-interval '1 hour'")){p.setObject(1,owner);try(var r=p.executeQuery()){r.next();if(r.getInt(1)>20)audit(c,owner,pair.pairingId(),"HIGH_FREQUENCY_REVIEW_SIGNAL");}}
            if(n==2){try(var p=c.prepareStatement("UPDATE ranked_pairings SET status='COMPLETED' WHERE pairing_id=?")){p.setObject(1,pair.pairingId());p.executeUpdate();}
                try(var p=c.prepareStatement("UPDATE ranked_queue_tickets SET status='COMPLETED' WHERE ticket_id IN (?,?) AND status='RUNNING'")){p.setObject(1,a.ticketId());p.setObject(2,b.ticketId());p.executeUpdate();}}
            return null;});
    }
    public void recover(){db.checkLease();db.transaction(c->{Database.lock(c,"ranked-queue");expire(c);try(var s=c.createStatement()){
        s.executeUpdate("UPDATE ranked_queue_tickets SET status='INTERRUPTED' WHERE status IN ('RESERVED','RUNNING')");
        s.executeUpdate("UPDATE ranked_pairings SET status='INTERRUPTED' WHERE status IN ('RESERVED','RUNNING')");
        s.executeUpdate("UPDATE ranked_games SET status='INTERRUPTED' WHERE status IN ('RESERVED','RUNNING')");
        s.executeUpdate("DELETE FROM matchmaking_audit_events WHERE created_at < now()-interval '30 days'");
        s.executeUpdate("DELETE FROM ranked_games WHERE pairing_id IN (SELECT pairing_id FROM ranked_pairings WHERE status IN ('COMPLETED','INTERRUPTED') AND created_at<now()-interval '30 days')");
        s.executeUpdate("DELETE FROM ranked_pairings WHERE status IN ('COMPLETED','INTERRUPTED') AND created_at<now()-interval '30 days' AND NOT EXISTS (SELECT 1 FROM matchmaking_audit_events a WHERE a.pairing_id=ranked_pairings.pairing_id)");
        s.executeUpdate("DELETE FROM ranked_queue_tickets WHERE status IN ('COMPLETED','CANCELLED','EXPIRED','INTERRUPTED') AND queued_at<now()-interval '30 days' AND NOT EXISTS (SELECT 1 FROM ranked_pairings p WHERE p.ticket_a=ranked_queue_tickets.ticket_id OR p.ticket_b=ranked_queue_tickets.ticket_id)");}return null;});}
    public List<RankedMatch> matches(UUID owner){db.checkLease();return db.transaction(c->{var out=new ArrayList<RankedMatch>();try(var p=c.prepareStatement("SELECT g.* FROM ranked_games g JOIN ranked_pairings p USING(pairing_id) JOIN ranked_queue_tickets a ON a.ticket_id=p.ticket_a JOIN ranked_queue_tickets b ON b.ticket_id=p.ticket_b WHERE a.owner_id=? OR b.owner_id=? ORDER BY p.created_at DESC,g.game_number LIMIT 100")){
        p.setObject(1,owner);p.setObject(2,owner);try(var r=p.executeQuery()){while(r.next()){
            UUID id=r.getObject("match_id",UUID.class);var changes=new ArrayList<Map<String,Object>>();try(var q=c.prepareStatement("SELECT bot_id,elo_before,elo_after FROM rating_events WHERE match_id=?")){q.setObject(1,id);try(var x=q.executeQuery()){while(x.next())changes.add(Map.of("botId",x.getObject(1),"before",x.getDouble(2),"after",x.getDouble(3)));}}
            out.add(new RankedMatch(id,r.getObject("pairing_id",UUID.class),r.getInt("game_number"),r.getString("status"),r.getString("snapshot")==null?null:PersistenceJson.mapper().readValue(r.getString("snapshot"),LocalArenaService.Snapshot.class),changes));
        }}}return List.copyOf(out);});}
    public RankedMatch match(UUID owner,UUID id){return matches(owner).stream().filter(m->m.matchId().equals(id)).findFirst().orElseThrow(()->new NoSuchElementException("Ranked match"));}
    public Map<UUID,Double> ratings(UUID owner){db.checkLease();return db.transaction(c->{var values=new LinkedHashMap<UUID,Double>();try(var p=c.prepareStatement("SELECT b.id,coalesce(r.elo,1500) FROM bots b LEFT JOIN rating_profiles r ON r.bot_id=b.id AND r.game_mode=? AND r.rules_version=? WHERE b.owner_id=? AND b.deleted_at IS NULL")){p.setString(1,MODE);p.setString(2,MODE);p.setObject(3,owner);try(var r=p.executeQuery()){while(r.next())values.put(r.getObject(1,UUID.class),r.getDouble(2));}}return Map.copyOf(values);});}
}
