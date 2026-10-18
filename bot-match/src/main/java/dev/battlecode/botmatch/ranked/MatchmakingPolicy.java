package dev.battlecode.botmatch.ranked;
import java.time.*;
import java.util.*;
public record MatchmakingPolicy(String version,int capacity,int lifetimeSeconds,int repeatSeconds,int rateCount,int rateSeconds,List<Window> windows){
    public record Window(int afterSeconds,int elo){public Window{if(afterSeconds<0||elo<1||elo>350)throw new IllegalArgumentException("Window");}}
    public static final MatchmakingPolicy V1=new MatchmakingPolicy("matchmaking-v1",64,600,86400,20,600,List.of(new Window(0,100),new Window(30,150),new Window(60,250),new Window(120,350)));
    public MatchmakingPolicy{windows=List.copyOf(windows);if(version==null||capacity<2||lifetimeSeconds<120||repeatSeconds<1||rateCount<1||rateSeconds<1||windows.isEmpty()||windows.getFirst().afterSeconds()!=0)throw new IllegalArgumentException("Policy");for(int i=1;i<windows.size();i++)if(windows.get(i).afterSeconds()<=windows.get(i-1).afterSeconds()||windows.get(i).elo()<windows.get(i-1).elo())throw new IllegalArgumentException("Window order");}
    public int window(Duration wait){int value=windows.getFirst().elo();for(var w:windows)if(wait.getSeconds()>=w.afterSeconds())value=w.elo();return value;}
}
