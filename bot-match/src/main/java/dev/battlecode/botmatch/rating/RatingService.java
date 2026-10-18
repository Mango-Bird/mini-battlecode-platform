package dev.battlecode.botmatch.rating;

import dev.battlecode.botmatch.persistence.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Ledger, Elo and Glicko admission commit together with the authoritative result checkpoint.
 * All writers take one transaction advisory lock; bounded local tournaments make this sufficient.
 * No network execution under this lock. */
public final class RatingService {
    private final Database db;
    private final RatingPolicy policy;
    private final Clock clock;
    private final ObjectMapper json=PersistenceJson.mapper();
    public RatingService(Database db){this(db,RatingPolicy.V1,Clock.systemUTC());}
    public RatingService(Database db,RatingPolicy policy,Clock clock){this.db=db;this.policy=policy;this.clock=clock;}
    private long period(){return Math.floorDiv(clock.instant().getEpochSecond(),policy.periodSeconds());}
    private record Profile(UUID bot,String mode,String rules,double elo,int games,Glicko2RatingEngine.Rating rating,long period){}
    public boolean confirm(Connection c,ConfirmedMatchResult result)throws Exception{
        Database.lock(c,"rating-ledger");
        try(var p=c.prepareStatement("SELECT result_hash FROM match_scoring_ledger WHERE match_id=?")){
            p.setObject(1,result.matchId());try(var r=p.executeQuery()){if(r.next()){if(!r.getString(1).equals(result.hash()))throw new IllegalStateException("Result conflict");return false;}}
        }
        for(var bot:List.of(result.a(),result.b()))try(var p=c.prepareStatement("SELECT 1 FROM bots b JOIN bot_versions v ON b.id=v.bot_id WHERE b.id=? AND b.owner_id=? AND v.version=? AND v.source_sha256=?")){
            p.setObject(1,bot.botId());p.setObject(2,bot.ownerId());p.setInt(3,bot.version());p.setString(4,bot.sourceSha256());try(var r=p.executeQuery()){if(!r.next())throw new SecurityException("Frozen identity mismatch");}}
        long period=period();closeDue(c,period);
        if(result.eligible()){
            try(var p=c.prepareStatement("INSERT INTO rating_periods VALUES (?,?,'OPEN',?,?,NULL) ON CONFLICT DO NOTHING")){
                p.setLong(1,period);p.setString(2,policy.version());p.setObject(3,OffsetDateTime.ofInstant(Instant.ofEpochSecond(period*policy.periodSeconds()),ZoneOffset.UTC));
                p.setObject(4,OffsetDateTime.ofInstant(Instant.ofEpochSecond((period+1)*policy.periodSeconds()),ZoneOffset.UTC));p.executeUpdate();}
        }
        try(var p=c.prepareStatement("INSERT INTO match_scoring_ledger(match_id,result_hash,canonical_result,eligible,policy_version,completed_at,period_id) VALUES (?,?,?::jsonb,?,?,?,?)")){
            p.setObject(1,result.matchId());p.setString(2,result.hash());p.setString(3,json.writeValueAsString(result));p.setBoolean(4,result.eligible());p.setString(5,policy.version());
            p.setObject(6,OffsetDateTime.ofInstant(result.completedAt(),ZoneOffset.UTC));p.setObject(7,result.eligible()?period:null);p.executeUpdate();}
        if(!result.eligible())return true;
        Profile a=profile(c,result.a().botId(),result.gameMode(),result.rulesVersion(),period),b=profile(c,result.b().botId(),result.gameMode(),result.rulesVersion(),period);
        var update=new EloRatingEngine().update(a.elo,a.games,b.elo,b.games,result.scoreA(),policy);
        event(c,result,a,b,result.scoreA(),update.a(),period);event(c,result,b,a,1-result.scoreA(),update.b(),period);
        return true;
    }
    public boolean confirm(ConfirmedMatchResult r){return db.transaction(c->confirm(c,r));}
    private Profile profile(Connection c,UUID bot,String mode,String rules,long period)throws SQLException{
        try(var p=c.prepareStatement("INSERT INTO rating_profiles(bot_id,game_mode,rules_version,policy_version,elo,rating,rd,volatility,rating_period) VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING")){
            p.setObject(1,bot);p.setString(2,mode);p.setString(3,rules);p.setString(4,policy.version());p.setDouble(5,policy.initialRating());p.setDouble(6,policy.initialRating());p.setDouble(7,policy.initialRd());p.setDouble(8,policy.initialVolatility());p.setLong(9,period);p.executeUpdate();}
        try(var p=c.prepareStatement("SELECT elo,games,rating,rd,volatility,rating_period,policy_version FROM rating_profiles WHERE bot_id=? AND game_mode=? AND rules_version=? FOR UPDATE")){
            p.setObject(1,bot);p.setString(2,mode);p.setString(3,rules);try(var r=p.executeQuery()){r.next();if(!policy.version().equals(r.getString(7)))throw new IllegalStateException("Rating policy migration required");
                return new Profile(bot,mode,rules,r.getDouble(1),r.getInt(2),new Glicko2RatingEngine.Rating(r.getDouble(3),r.getDouble(4),r.getDouble(5)),r.getLong(6));}}
    }
    private void event(Connection c,ConfirmedMatchResult result,Profile p,Profile opponent,double score,double elo,long period)throws SQLException{
        try(var s=c.prepareStatement("INSERT INTO rating_events(match_id,bot_id,game_mode,rules_version,period_id,opponent_bot_id,score,elo_before,elo_after,k,rating_before,rd_before,volatility_before,opponent_rating,opponent_rd,opponent_volatility) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")){
            s.setObject(1,result.matchId());s.setObject(2,p.bot);s.setString(3,p.mode);s.setString(4,p.rules);s.setLong(5,period);s.setObject(6,opponent.bot);s.setDouble(7,score);s.setDouble(8,p.elo);s.setDouble(9,elo);s.setDouble(10,policy.k(p.games));
            s.setDouble(11,p.rating.rating());s.setDouble(12,p.rating.ratingDeviation());s.setDouble(13,p.rating.volatility());s.setDouble(14,opponent.rating.rating());s.setDouble(15,opponent.rating.ratingDeviation());s.setDouble(16,opponent.rating.volatility());s.executeUpdate();}
        try(var s=c.prepareStatement("UPDATE rating_profiles SET elo=?,games=games+1,games_in_period=games_in_period+1,wins=wins+?,draws=draws+?,losses=losses+? WHERE bot_id=? AND game_mode=? AND rules_version=?")){
            s.setDouble(1,elo);s.setInt(2,score==1?1:0);s.setInt(3,score==.5?1:0);s.setInt(4,score==0?1:0);s.setObject(5,p.bot);s.setString(6,p.mode);s.setString(7,p.rules);s.executeUpdate();}
    }
    public void closeDue(){db.transaction(c->{Database.lock(c,"rating-ledger");closeDue(c,period());return null;});}
    private void closeDue(Connection c,long current)throws SQLException{
        var profiles=new ArrayList<Profile>();
        try(var s=c.createStatement();var r=s.executeQuery("SELECT bot_id,game_mode,rules_version,elo,games,rating,rd,volatility,rating_period,policy_version FROM rating_profiles ORDER BY bot_id,game_mode,rules_version FOR UPDATE")){
            while(r.next()){
                if(!policy.version().equals(r.getString(10)))throw new IllegalStateException("Rating policy migration required");
                profiles.add(new Profile(r.getObject(1,UUID.class),r.getString(2),r.getString(3),r.getDouble(4),r.getInt(5),new Glicko2RatingEngine.Rating(r.getDouble(6),r.getDouble(7),r.getDouble(8)),r.getLong(9)));}}
        for(var p:profiles){
            if(p.period>current)throw new IllegalStateException("Clock moved backwards");
            var rating=p.rating;
            for(long n=p.period;n<current;n++){
                var opponents=new ArrayList<Glicko2RatingEngine.Opponent>();
                try(var s=c.prepareStatement("SELECT opponent_rating,opponent_rd,opponent_volatility,score FROM rating_events WHERE period_id=? AND bot_id=? AND game_mode=? AND rules_version=? ORDER BY sequence")){
                    s.setLong(1,n);s.setObject(2,p.bot);s.setString(3,p.mode);s.setString(4,p.rules);try(var r=s.executeQuery()){while(r.next())opponents.add(new Glicko2RatingEngine.Opponent(new Glicko2RatingEngine.Rating(r.getDouble(1),r.getDouble(2),r.getDouble(3)),r.getDouble(4)));}}
                var before=rating;rating=new Glicko2RatingEngine().update(before,opponents,policy.tau());
                // Empty periods also record uncertainty growth for deterministic rebuild.
                try(var s=c.prepareStatement("INSERT INTO rating_periods VALUES (?,?,'CLOSED',?,?,now()) ON CONFLICT DO NOTHING")){
                    s.setLong(1,n);s.setString(2,policy.version());s.setObject(3,OffsetDateTime.ofInstant(Instant.ofEpochSecond(n*policy.periodSeconds()),ZoneOffset.UTC));s.setObject(4,OffsetDateTime.ofInstant(Instant.ofEpochSecond((n+1)*policy.periodSeconds()),ZoneOffset.UTC));s.executeUpdate();}
                try(var s=c.prepareStatement("INSERT INTO rating_period_updates VALUES (?,?,?,?,?,?,?,?,?,?,?)")){
                    s.setLong(1,n);s.setObject(2,p.bot);s.setString(3,p.mode);s.setString(4,p.rules);s.setDouble(5,before.rating());s.setDouble(6,before.ratingDeviation());s.setDouble(7,before.volatility());s.setDouble(8,rating.rating());s.setDouble(9,rating.ratingDeviation());s.setDouble(10,rating.volatility());s.setInt(11,opponents.size());s.executeUpdate();}
            }
            if(p.period<current)try(var s=c.prepareStatement("UPDATE rating_profiles SET rating=?,rd=?,volatility=?,rating_period=?,games_in_period=0 WHERE bot_id=? AND game_mode=? AND rules_version=?")){
                s.setDouble(1,rating.rating());s.setDouble(2,rating.ratingDeviation());s.setDouble(3,rating.volatility());s.setLong(4,current);s.setObject(5,p.bot);s.setString(6,p.mode);s.setString(7,p.rules);s.executeUpdate();}
        }
        try(var s=c.prepareStatement("UPDATE rating_periods SET state='CLOSED',closed_at=now() WHERE id<? AND state='OPEN'")){s.setLong(1,current);s.executeUpdate();}
    }
    public List<Map<String,Object>> leaderboard(String mode,String rules,boolean advanced){
        closeDue();return leaderboardSnapshot(mode,rules,advanced);
    }
    /** Pure read for execution-disabled staging; never closes or writes rating periods. */
    public List<Map<String,Object>> leaderboardSnapshot(String mode,String rules,boolean advanced){
        return db.transaction(c->{c.setReadOnly(true);var list=new ArrayList<Map<String,Object>>();
            String order=advanced?"p.rating DESC":"p.elo DESC";
            try(var s=c.prepareStatement("SELECT p.*,b.name FROM rating_profiles p JOIN bots b ON b.id=p.bot_id WHERE p.game_mode=? AND p.rules_version=? AND p.games>0 AND b.deleted_at IS NULL ORDER BY "+order+",p.bot_id LIMIT 100")){
                s.setString(1,mode);s.setString(2,rules);try(var r=s.executeQuery()){int rank=0;while(r.next()){
                    var row=new LinkedHashMap<String,Object>();row.put("rank",++rank);row.put("botId",r.getObject("bot_id"));row.put("name",r.getString("name"));row.put("elo",r.getDouble("elo"));row.put("glicko2",r.getDouble("rating"));row.put("rd",r.getDouble("rd"));row.put("volatility",r.getDouble("volatility"));row.put("ratingPeriod",r.getLong("rating_period"));row.put("gamesInPeriod",r.getInt("games_in_period"));row.put("wins",r.getInt("wins"));row.put("draws",r.getInt("draws"));row.put("losses",r.getInt("losses"));row.put("provisional",r.getInt("games")<policy.firstThreshold());list.add(row);
                }}}
            return List.copyOf(list);});
    }
    public List<Map<String,Object>> history(UUID bot){return db.transaction(c->{var list=new ArrayList<Map<String,Object>>();try(var s=c.prepareStatement("SELECT match_id,game_mode,rules_version,score,elo_before,elo_after,period_id FROM rating_events WHERE bot_id=? ORDER BY sequence DESC LIMIT 100")){
        s.setObject(1,bot);try(var r=s.executeQuery()){while(r.next())list.add(Map.of("matchId",r.getObject(1),"gameMode",r.getString(2),"rulesVersion",r.getString(3),"score",r.getDouble(4),"eloBefore",r.getDouble(5),"eloAfter",r.getDouble(6),"ratingPeriod",r.getLong(7)));}}return List.copyOf(list);});}
    public List<Map<String,Object>> periodHistory(UUID bot){closeDue();return db.transaction(c->{var list=new ArrayList<Map<String,Object>>();try(var p=c.prepareStatement("SELECT period_id,game_mode,rules_version,rating_before,rating_after,rd_after,volatility_after,games FROM rating_period_updates WHERE bot_id=? ORDER BY period_id DESC LIMIT 100")){
        p.setObject(1,bot);try(var r=p.executeQuery()){while(r.next())list.add(Map.of("ratingPeriod",r.getLong(1),"gameMode",r.getString(2),"rulesVersion",r.getString(3),"before",r.getDouble(4),"after",r.getDouble(5),"rd",r.getDouble(6),"volatility",r.getDouble(7),"games",r.getInt(8)));}}return List.copyOf(list);});}
    /** Administrative recovery only, no HTTP mutation endpoint. Rebuilds mutable projections from
     * append-only events/period updates in their recorded order; immutable history remains intact. */
    public void rebuildProfiles(){db.transaction(c->{Database.lock(c,"rating-ledger");
        try(var s=c.createStatement();var profiles=s.executeQuery("SELECT bot_id,game_mode,rules_version,rating_period FROM rating_profiles ORDER BY bot_id,game_mode,rules_version FOR UPDATE")){
            while(profiles.next()){
                UUID bot=profiles.getObject(1,UUID.class);String mode=profiles.getString(2),rules=profiles.getString(3);long period=profiles.getLong(4);
                double elo=policy.initialRating();int games=0,wins=0,draws=0,losses=0,inPeriod=0;
                try(var p=c.prepareStatement("SELECT e.elo_before,e.elo_after,e.k,e.score,e.period_id,o.elo_before FROM rating_events e JOIN rating_events o ON o.match_id=e.match_id AND o.bot_id=e.opponent_bot_id WHERE e.bot_id=? AND e.game_mode=? AND e.rules_version=? ORDER BY e.sequence")){
                    p.setObject(1,bot);p.setString(2,mode);p.setString(3,rules);try(var r=p.executeQuery()){while(r.next()){
                        if(Math.abs(elo-r.getDouble(1))>1e-8||policy.k(games)!=r.getDouble(3))throw new IllegalStateException("Rating audit mismatch");
                        double score=r.getDouble(4),computed=new EloRatingEngine().update(elo,games,r.getDouble(6),0,score,policy).a();
                        if(Math.abs(computed-r.getDouble(2))>1e-8)throw new IllegalStateException("Elo formula mismatch");
                        elo=computed;games++;if(score==1)wins++;else if(score==.5)draws++;else losses++;if(r.getLong(5)==period)inPeriod++;
                    }}}
                var rating=new Glicko2RatingEngine.Rating(policy.initialRating(),policy.initialRd(),policy.initialVolatility());
                try(var p=c.prepareStatement("SELECT period_id,rating_before,rd_before,volatility_before,rating_after,rd_after,volatility_after FROM rating_period_updates WHERE bot_id=? AND game_mode=? AND rules_version=? ORDER BY period_id")){
                    p.setObject(1,bot);p.setString(2,mode);p.setString(3,rules);try(var r=p.executeQuery()){while(r.next()){
                        if(Math.abs(rating.rating()-r.getDouble(2))>1e-8||Math.abs(rating.ratingDeviation()-r.getDouble(3))>1e-8||Math.abs(rating.volatility()-r.getDouble(4))>1e-10)throw new IllegalStateException("Period audit mismatch");
                        var opponents=new ArrayList<Glicko2RatingEngine.Opponent>();
                        try(var q=c.prepareStatement("SELECT opponent_rating,opponent_rd,opponent_volatility,score FROM rating_events WHERE bot_id=? AND game_mode=? AND rules_version=? AND period_id=? ORDER BY sequence")){
                            q.setObject(1,bot);q.setString(2,mode);q.setString(3,rules);q.setLong(4,r.getLong(1));try(var o=q.executeQuery()){while(o.next())opponents.add(new Glicko2RatingEngine.Opponent(new Glicko2RatingEngine.Rating(o.getDouble(1),o.getDouble(2),o.getDouble(3)),o.getDouble(4)));}}
                        rating=new Glicko2RatingEngine().update(rating,opponents,policy.tau());
                        if(Math.abs(rating.rating()-r.getDouble(5))>1e-8||Math.abs(rating.ratingDeviation()-r.getDouble(6))>1e-8||Math.abs(rating.volatility()-r.getDouble(7))>1e-10)throw new IllegalStateException("Period formula mismatch");
                    }}}
                try(var p=c.prepareStatement("UPDATE rating_profiles SET elo=?,rating=?,rd=?,volatility=?,games=?,wins=?,draws=?,losses=?,games_in_period=? WHERE bot_id=? AND game_mode=? AND rules_version=?")){
                    p.setDouble(1,elo);p.setDouble(2,rating.rating());p.setDouble(3,rating.ratingDeviation());p.setDouble(4,rating.volatility());p.setInt(5,games);p.setInt(6,wins);p.setInt(7,draws);p.setInt(8,losses);p.setInt(9,inPeriod);p.setObject(10,bot);p.setString(11,mode);p.setString(12,rules);p.executeUpdate();}
            }}return null;});}
}
