package dev.battlecode.botmatch.tournament;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
/** Scheduling/scoring only; contains no OTT game logic. */
public final class TournamentRules {
    private TournamentRules(){}
    public static List<UUID> seeded(List<TournamentEntry> entries,long seed) {
        return entries.stream().sorted(Comparator.comparing((TournamentEntry e)->key(seed,e.ownerId(),e.botId(),e.version()))
            .thenComparing(e->e.entryId().toString())).map(TournamentEntry::entryId).toList();
    }
    private static String key(long seed,UUID owner,UUID bot,int version) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((seed+":"+owner+":"+bot+":"+version).getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    public static List<TournamentMatch> roundRobin(UUID tournament,List<UUID> order) {
        var matches=new ArrayList<TournamentMatch>();int round=0;
        for(int i=0;i<order.size();i++)for(int j=i+1;j<order.size();j++) {
            matches.add(match(tournament,++round,0,0,order.get(i),order.get(j)));
            matches.add(match(tournament,++round,0,0,order.get(j),order.get(i)));
        }
        return List.copyOf(matches);
    }
    public static List<TournamentMatch> elimination(UUID tournament,int round,List<UUID> order) {
        int size=1;while(size<order.size())size*=2;
        var matches=new ArrayList<TournamentMatch>();int pairs=order.size()-size/2,index=0,slot=0;
        // Top seed positions receive the exact number of BYEs; future rounds are made only after completion.
        for(int i=0;i<size-order.size();i++)matches.add(match(tournament,round,slot++,0,order.get(index++),null));
        for(int i=0;i<pairs;i++)matches.add(match(tournament,round,slot++,0,order.get(index++),order.get(index++)));
        return List.copyOf(matches);
    }
    public static TournamentMatch match(UUID tournament,int round,int slot,int attempt,UUID a,UUID b) {
        UUID id=UUID.nameUUIDFromBytes((tournament+":"+round+":"+slot+":"+attempt).getBytes(StandardCharsets.UTF_8));
        return new TournamentMatch(id,round,slot,attempt,a,b,b==null?"BYE":"PENDING",null,b==null?a:null,null);
    }
    public static List<TournamentStanding> standings(List<TournamentEntry> entries,List<TournamentMatch> matches) {
        Map<UUID,int[]> totals=new HashMap<>();entries.forEach(e->totals.put(e.entryId(),new int[6]));
        for(var m:matches) {
            if(!scorable(m))continue;
            int[] a=totals.get(m.entryA()),b=totals.get(m.entryB());a[0]++;b[0]++;
            if(m.result().outcome().equals("DRAW")){a[2]++;b[2]++;a[5]++;b[5]++;}
            else {int[] winner=m.winnerId().equals(m.entryA())?a:b,loser=winner==a?b:a;winner[1]++;winner[5]+=3;loser[3]++;if(m.result().outcome().equals("FORFEIT"))loser[4]++;}
        }
        Map<UUID,Integer> h2h=new HashMap<>();entries.forEach(e->h2h.put(e.entryId(),0));
        for(var m:matches)if(scorable(m)) {
            int[] a=totals.get(m.entryA()),b=totals.get(m.entryB());
            if(a[5]!=b[5]||a[1]!=b[1])continue;
            if(m.result().outcome().equals("DRAW")){h2h.merge(m.entryA(),1,Integer::sum);h2h.merge(m.entryB(),1,Integer::sum);}
            else h2h.merge(m.winnerId(),3,Integer::sum);
        }
        var sorted=entries.stream().sorted(Comparator.<TournamentEntry>comparingInt(e->totals.get(e.entryId())[5]).reversed()
            .thenComparing(Comparator.<TournamentEntry>comparingInt(e->totals.get(e.entryId())[1]).reversed())
            .thenComparing(Comparator.<TournamentEntry>comparingInt(e->h2h.get(e.entryId())).reversed()).thenComparingInt(TournamentEntry::seedOrder)).toList();
        var result=new ArrayList<TournamentStanding>();int rank=0;
        for(var e:sorted){int[] t=totals.get(e.entryId());result.add(new TournamentStanding(++rank,e.entryId(),e.botName(),e.ownerDisplay(),t[0],t[1],t[2],t[3],t[4],t[5]));}
        return List.copyOf(result);
    }
    private static boolean scorable(TournamentMatch m){
        return m.status().equals("FINISHED")&&m.entryB()!=null&&m.result()!=null
            &&(m.result().outcome().equals("DRAW")||Set.of("WIN","FORFEIT").contains(m.result().outcome())
                &&(m.entryA().equals(m.winnerId())||m.entryB().equals(m.winnerId())));
    }
}
