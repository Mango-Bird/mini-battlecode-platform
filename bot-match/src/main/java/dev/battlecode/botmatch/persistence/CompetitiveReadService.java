package dev.battlecode.botmatch.persistence;
import dev.battlecode.botmatch.LocalArenaService;
import dev.battlecode.botmatch.rating.*;
import java.util.*;
import java.sql.*;
import java.time.Instant;

/** Read-only participant projections. No source lookup or worker execution. */
public final class CompetitiveReadService {
    private final Database db;
    public CompetitiveReadService(Database db){this.db=db;}
    private <T>T readTransaction(Database.Work<T> work){return db.transaction(c->{c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setReadOnly(true);return work.run(c);});}
    public record Bot(UUID botId,String name,int version,String ownerDisplay,Double eloBefore){}
    public record Match(UUID matchId,String kind,String gameMode,String rulesVersion,String status,Instant date,
                        Bot a,Bot b,LocalArenaService.Snapshot snapshot,List<Map<String,Object>> ratingChanges){public Match{ratingChanges=List.copyOf(ratingChanges);}}
    public Match match(UUID viewer,UUID id){db.checkLease();return readTransaction(c->read(c,viewer,id,false));}
    private Match read(Connection c,UUID viewer,UUID id,boolean summary)throws Exception{
        String sql="SELECT g.status,g.snapshot::text,p.created_at,g.game_number,a.bot_id,a.bot_version,ba.name,ua.username,b.bot_id,b.bot_version,bb.name,ub.username FROM ranked_games g JOIN ranked_pairings p USING(pairing_id) JOIN ranked_queue_tickets a ON a.ticket_id=p.ticket_a JOIN ranked_queue_tickets b ON b.ticket_id=p.ticket_b JOIN bots ba ON ba.id=a.bot_id JOIN bots bb ON bb.id=b.bot_id JOIN users ua ON ua.id=a.owner_id JOIN users ub ON ub.id=b.owner_id WHERE g.match_id=? AND (a.owner_id=? OR b.owner_id=?)";
        sql=sql.replace("g.snapshot::text",snapshotColumn("g.snapshot",summary)).replace("m.replay_snapshot::text",snapshotColumn("m.replay_snapshot",summary));try(var p=c.prepareStatement(sql)){p.setObject(1,id);p.setObject(2,viewer);p.setObject(3,viewer);try(var r=p.executeQuery()){if(r.next()){
            boolean swapped=r.getInt(4)==2;Bot a=new Bot(r.getObject(5,UUID.class),r.getString(7),r.getInt(6),r.getString(8),null),b=new Bot(r.getObject(9,UUID.class),r.getString(11),r.getInt(10),r.getString(12),null);
            return projection(c,id,"Ranked",r.getString(1),r.getTimestamp(3).toInstant(),swapped?b:a,swapped?a:b,r.getString(2),summary);
        }}}
        try(var p=c.prepareStatement("SELECT status,"+snapshotColumn("snapshot",summary)+",created_at FROM arena_battles WHERE battle_id=? AND owner_id=?")){p.setObject(1,id);p.setObject(2,viewer);try(var r=p.executeQuery()){if(r.next()){
            var snapshot=decode(r.getString(2));return projection(c,id,"Unranked",r.getString(1),r.getTimestamp(3).toInstant(),manualBot(c,snapshot.botA()),manualBot(c,snapshot.botB()),r.getString(2),summary);
        }}}
        sql="SELECT m.status,m.replay_snapshot::text,coalesce(m.started_at,t.created_at),a.bot_id,a.bot_version,ba.name,ua.username,b.bot_id,b.bot_version,bb.name,ub.username FROM tournament_matches m JOIN tournaments t ON t.id=m.tournament_id JOIN tournament_entries a ON a.id=m.bot_a_entry_id JOIN tournament_entries b ON b.id=m.bot_b_entry_id JOIN bots ba ON ba.id=a.bot_id JOIN bots bb ON bb.id=b.bot_id JOIN users ua ON ua.id=a.owner_id JOIN users ub ON ub.id=b.owner_id WHERE m.id=? AND (a.owner_id=? OR b.owner_id=?)";
        sql=sql.replace("g.snapshot::text",snapshotColumn("g.snapshot",summary)).replace("m.replay_snapshot::text",snapshotColumn("m.replay_snapshot",summary));try(var p=c.prepareStatement(sql)){p.setObject(1,id);p.setObject(2,viewer);p.setObject(3,viewer);try(var r=p.executeQuery()){if(r.next())return projection(c,id,"Tournament",r.getString(1),r.getTimestamp(3).toInstant(),new Bot(r.getObject(4,UUID.class),r.getString(6),r.getInt(5),r.getString(7),null),new Bot(r.getObject(8,UUID.class),r.getString(10),r.getInt(9),r.getString(11),null),r.getString(2),summary);}}
        throw new NoSuchElementException("Match");
    }
    private static String snapshotColumn(String field,boolean summary){if(!summary)return field+"::text";return "(CASE WHEN "+field+" IS NULL THEN NULL ELSE jsonb_build_object('id',"+field+"->'id','botA',"+field+"->'botA','botB',"+field+"->'botB','status',"+field+"->'status','turn',"+field+"->'turn','currentSide',"+field+"->'currentSide','board','[]'::jsonb,'moves','[]'::jsonb,'frames','[]'::jsonb,'result',"+field+"->'result') END)::text";}
    private static LocalArenaService.Snapshot decode(String value)throws Exception{return value==null?null:PersistenceJson.mapper().readValue(value,LocalArenaService.Snapshot.class);}
    private Bot manualBot(Connection c,String label)throws SQLException{
        if(label.startsWith("FirstLegalMoveBot")||label.startsWith("CapturePriorityBot"))return new Bot(null,label,1,null,null);
        try{String[] parts=label.split(":");UUID id=UUID.fromString(parts[0]);int version=Integer.parseInt(parts[1].substring(1));try(var p=c.prepareStatement("SELECT b.name,u.username FROM bots b JOIN users u ON u.id=b.owner_id WHERE b.id=?")){p.setObject(1,id);try(var r=p.executeQuery()){if(r.next())return new Bot(id,r.getString(1),version,r.getString(2),null);}}}catch(IllegalArgumentException ignored){}
        return new Bot(null,"Saved bot",0,null,null);
    }
    private Match projection(Connection c,UUID id,String kind,String status,Instant date,Bot a,Bot b,String raw,boolean summary)throws Exception{
        var changes=new ArrayList<Map<String,Object>>();Double eloA=null,eloB=null;
        try(var p=c.prepareStatement("SELECT bot_id,elo_before,elo_after FROM rating_events WHERE match_id=?")){p.setObject(1,id);try(var r=p.executeQuery()){while(r.next()){UUID bot=r.getObject(1,UUID.class);double before=r.getDouble(2);changes.add(Map.of("botId",bot,"before",before,"after",r.getDouble(3)));if(bot.equals(a.botId()))eloA=before;if(bot.equals(b.botId()))eloB=before;}}}
        var snapshot=decode(raw);
        if(!summary&&snapshot!=null&&(snapshot.moves().size()>1000||snapshot.frames().size()!=snapshot.moves().size()+1||!snapshot.frames().getLast().equals(snapshot.board())))throw new IllegalStateException("Replay integrity refused");
        if(snapshot!=null)snapshot=new LocalArenaService.Snapshot(snapshot.id(),"Side A","Side B",snapshot.status(),snapshot.turn(),snapshot.currentSide(),snapshot.board(),snapshot.moves(),snapshot.frames(),snapshot.result());
        return new Match(id,kind,"ott-board-9x9-v1","ott-board-9x9-v1",status,date,new Bot(a.botId(),a.name(),a.version(),a.ownerDisplay(),eloA),new Bot(b.botId(),b.name(),b.version(),b.ownerDisplay(),eloB),snapshot,changes);
    }
    public List<Match> history(UUID viewer){return history(viewer,0);}
    public List<Match> history(UUID viewer,int page){if(page<0||page>200)throw new IllegalArgumentException("History page");db.checkLease();return readTransaction(c->{var ids=new ArrayList<UUID>();
        String sql="SELECT id FROM (SELECT g.match_id AS id,p.created_at AS date FROM ranked_games g JOIN ranked_pairings p USING(pairing_id) JOIN ranked_queue_tickets a ON a.ticket_id=p.ticket_a JOIN ranked_queue_tickets b ON b.ticket_id=p.ticket_b WHERE a.owner_id=? OR b.owner_id=? UNION ALL SELECT battle_id,created_at FROM arena_battles WHERE owner_id=? AND battle_id IS NOT NULL UNION ALL SELECT m.id,coalesce(m.started_at,t.created_at) FROM tournament_matches m JOIN tournaments t ON t.id=m.tournament_id JOIN tournament_entries a ON a.id=m.bot_a_entry_id JOIN tournament_entries b ON b.id=m.bot_b_entry_id WHERE a.owner_id=? OR b.owner_id=?) h ORDER BY date DESC,id LIMIT 50";
        try(var p=c.prepareStatement(sql+" OFFSET ?")){for(int n=1;n<=5;n++)p.setObject(n,viewer);p.setInt(6,page*50);try(var r=p.executeQuery()){while(r.next())ids.add(r.getObject(1,UUID.class));}}
        var result=new ArrayList<Match>();for(UUID id:ids){var m=read(c,viewer,id,true);result.add(new Match(m.matchId(),m.kind(),m.gameMode(),m.rulesVersion(),m.status(),m.date(),m.a(),m.b(),m.snapshot()==null?null:new LocalArenaService.Snapshot(m.snapshot().id(),"A","B",m.snapshot().status(),m.snapshot().turn(),m.snapshot().currentSide(),List.of(),List.of(),List.of(),m.snapshot().result()),m.ratingChanges()));}return List.copyOf(result);});}
    public Map<String,Object> profile(UUID viewer,UUID id){db.checkLease();return readTransaction(c->{var value=new LinkedHashMap<String,Object>();
        try(var p=c.prepareStatement("SELECT b.name,u.username,b.owner_id,b.deleted_at,r.elo,r.rating,r.rd,r.volatility,r.games,r.wins,r.draws,r.losses FROM bots b JOIN users u ON u.id=b.owner_id LEFT JOIN rating_profiles r ON r.bot_id=b.id AND r.game_mode='ott-board-9x9-v1' AND r.rules_version='ott-board-9x9-v1' WHERE b.id=? AND (b.owner_id=? OR r.games>0)")){
            p.setObject(1,id);p.setObject(2,viewer);try(var r=p.executeQuery()){if(!r.next())throw new NoSuchElementException("Bot profile");value.put("botId",id);value.put("name",r.getString(1));value.put("ownerDisplay",r.getString(2));value.put("canEdit",viewer.equals(r.getObject(3,UUID.class))&&r.getTimestamp(4)==null);value.put("elo",r.getObject(5)==null?1500:r.getDouble(5));value.put("glicko2",r.getObject(6)==null?1500:r.getDouble(6));value.put("rd",r.getObject(7)==null?350:r.getDouble(7));value.put("volatility",r.getObject(8)==null?.06:r.getDouble(8));value.put("games",r.getInt(9));value.put("wins",r.getInt(10));value.put("draws",r.getInt(11));value.put("losses",r.getInt(12));value.put("provisional",r.getInt(9)<20);}}
        value.put("gameMode","ott-board-9x9-v1");value.put("rulesVersion","ott-board-9x9-v1");return value;});}
}
