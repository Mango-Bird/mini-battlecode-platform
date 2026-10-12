package dev.battlecode.server;

import dev.battlecode.rulesapi.*;
import java.util.*;

/** Test fixture only; never registered by ServerMain. */
final class FakeMatchRule implements GameRule {
    record State(List<UUID> players, int count, Set<UUID> connected) implements RuleState {
        State { players=List.copyOf(players); connected=Set.copyOf(connected); }
    }
    final String id;
    final boolean simultaneous;
    volatile boolean explode;
    volatile boolean explodeInitial;
    volatile boolean finishOnDisconnect;
    final int finishAt;
    FakeMatchRule(String id,boolean simultaneous,int finishAt){this.id=id;this.simultaneous=simultaneous;this.finishAt=finishAt;}
    public String id(){return id;}
    public void validateConfig(GameConfig config){}
    public RuleState initialState(GameConfig config,List<UUID> players){if(explodeInitial)throw new IllegalStateException("test initialization failure");return new State(players,0,new HashSet<>(players));}
    public UUID currentPlayer(RuleState raw){State s=(State)raw;UUID first=s.players().get(s.count()%s.players().size());return s.connected().contains(first)?first:s.connected().iterator().next();}
    public Set<UUID> activePlayers(RuleState raw){return simultaneous?((State)raw).connected():GameRule.super.activePlayers(raw);}
    public boolean isTurnComplete(RuleState before,RuleState after){return !simultaneous || ((State)after).count()%2==0;}
    public boolean isActionAllowed(RuleState state,UUID player,Map<String,String> action){return action.equals(Map.of("move","ok"));}
    public RuleState applyAction(RuleState raw,UUID player,Map<String,String> action){if(explode)throw new IllegalStateException("test-only failure");State s=(State)raw;return new State(s.players(),s.count()+1,s.connected());}
    public RuleState onTurnTimeout(RuleState raw,UUID player){return applyAction(raw,player,Map.of("move","ok"));}
    public RuleState onDisconnect(RuleState raw,UUID player){State s=(State)raw;var connected=new HashSet<>(s.connected());connected.remove(player);return new State(s.players(),finishOnDisconnect?finishAt:s.count(),connected);}
    public RuleOutcome evaluate(RuleState raw){State s=(State)raw;boolean done=s.count()>=finishAt;return new RuleOutcome(done,done?Set.of(s.players().get(0)):Set.of(),"FAKE_FINISHED");}
    public Map<String,String> viewFor(RuleState raw,UUID player){return Map.of("self",player.toString(),"count",Integer.toString(((State)raw).count()),"rule",id);}
}
