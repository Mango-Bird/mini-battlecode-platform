package dev.battlecode.botmatch;

import dev.battlecode.botrunner.*;
import dev.battlecode.competition.*;
import dev.battlecode.engine.*;
import dev.battlecode.rulesapi.*;
import dev.battlecode.rulesimpl.OttBoardRule;
import dev.battlecode.rulesimpl.board.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Bounded trusted catalog and public projections; one shared engine/manager for all arena battles. */
public final class LocalArenaService implements AutoCloseable {
    public static final List<String> BOTS=List.of("FirstLegalMoveBot","CapturePriorityBot");
    public record Result(String outcome,String winner,String reason) { }
    public record Snapshot(UUID id,String botA,String botB,String status,int turn,String currentSide,
                           List<Piece> board,List<MoveEvent> moves,List<List<Piece>> frames,Result result) {
        public Snapshot {board=List.copyOf(board);moves=List.copyOf(moves);frames=frames.stream().map(List::copyOf).toList();}
    }
    private static final class Battle {
        final String a,b; final UUID request; volatile Snapshot snapshot;
        Battle(String a,String b,UUID request) {this.a=a;this.b=b;this.request=request;}
    }
    private final Path executable;
    private final VerifiedWorkerRunner isolated;
    private final OttBoardRule rule=new OttBoardRule();
    private final InMemoryRuleRegistry registry=new InMemoryRuleRegistry(List.of(rule));
    private final InMemoryLobby lobby=new InMemoryLobby(registry);
    private final DefaultMatchManager manager=new DefaultMatchManager(lobby,new DefaultGameEngine(),registry,n->{});
    private final Map<UUID,Battle> battles=new LinkedHashMap<>();
    private BotMatchCoordinator coordinator;
    private BotExecutionService executions;
    private Battle active;
    private boolean closed;
    private UUID tournamentReservation;
    private java.util.function.BiConsumer<UUID,Snapshot> snapshotObserver=(request,snapshot)->{};
    public synchronized void observeManualSnapshots(java.util.function.BiConsumer<UUID,Snapshot> observer){if(active!=null)throw new IllegalStateException("Arena already used");snapshotObserver=Objects.requireNonNull(observer);}
    public void cancelBattle(UUID id){BotMatchCoordinator current;synchronized(this){current=coordinator;}if(current!=null)current.cancel(id);}
    public LocalArenaService(Path executable) {this(executable,null);}
    public LocalArenaService(Path executable,VerifiedWorkerRunner isolated) {this.executable=executable;this.isolated=isolated;}
    public boolean userBotsExecutable(){return isolated!=null&&isolated.capabilities().executable();}
    public Map<String,Object> executionCapabilities() {
        return Map.of("executable",userBotsExecutable(),"controls",isolated==null?Map.of():isolated.capabilities().controls(),
            "reason",userBotsExecutable()?"Mandatory local sandbox controls verified":"Mandatory sandbox controls unavailable or unverified; user bots remain NOT_EXECUTABLE");
    }
    public synchronized Snapshot start(String a,String b,UUID request) {
        if(tournamentReservation!=null)throw new IllegalStateException("Tournament owns arena admission");
        if(closed)throw new IllegalStateException("Arena closed");
        if(!BOTS.contains(a)||!BOTS.contains(b))throw new IllegalArgumentException("Unknown trusted bot");
        return startFrozen(a,b,request,TrustedBotFixtures.load(TrustedBotFixtures.Fixture.valueOf(a)),
            TrustedBotFixtures.load(TrustedBotFixtures.Fixture.valueOf(b)),false);
    }
    public synchronized Snapshot startSaved(BotSubmission a,BotSubmission b,UUID request) {
        if(tournamentReservation!=null)throw new IllegalStateException("Tournament owns arena admission");
        if(!userBotsExecutable())throw new IllegalStateException("Mandatory sandbox gate");
        return startFrozen(a.submissionId()+":v"+a.version()+":"+a.sourceSha256(),
            b.submissionId()+":v"+b.version()+":"+b.sourceSha256(),request,a,b,true);
    }
    public synchronized void reserveTournament(UUID id) {
        if(closed||!userBotsExecutable()||tournamentReservation!=null||active!=null&&active.snapshot.status().equals("RUNNING"))
            throw new IllegalStateException("Arena admission unavailable");
        tournamentReservation=id;
    }
    /** Same bounded admission slot as tournaments and manual Arena; no second executor. */
    public void reserveRanked(UUID id){reserveTournament(id);}
    public Snapshot startRankedBattle(UUID reservation,BotSubmission a,BotSubmission b,UUID attempt){return startTournamentBattle(reservation,a,b,attempt);}
    public void releaseRanked(UUID id){releaseTournament(id);}
    public void cancelRanked(UUID reservation,UUID battle){cancelTournamentBattle(reservation,battle);}
    public void archiveRanked(UUID reservation,UUID battle){archiveTournamentBattle(reservation,battle);}
    public synchronized Snapshot startTournamentBattle(UUID tournament,BotSubmission a,BotSubmission b,UUID attempt) {
        if(!tournament.equals(tournamentReservation)||!userBotsExecutable())throw new IllegalStateException("Tournament admission unavailable");
        return startFrozen(a.submissionId()+":v"+a.version()+":"+a.sourceSha256(),b.submissionId()+":v"+b.version()+":"+b.sourceSha256(),attempt,a,b,true);
    }
    public void cancelTournamentBattle(UUID tournament,UUID battle) {
        BotMatchCoordinator current;
        synchronized(this){if(!tournament.equals(tournamentReservation))return;current=coordinator;}
        if(current!=null)current.cancel(battle);
    }
    public synchronized void archiveTournamentBattle(UUID tournament,UUID battle) {
        if(!tournament.equals(tournamentReservation))throw new IllegalStateException("Reservation");
        Battle item=battles.get(battle);
        if(item!=null&&item.snapshot.status().equals("RUNNING"))throw new IllegalStateException("Battle still running");
        battles.remove(battle);if(active==item)active=null;
    }
    public synchronized void releaseTournament(UUID id) {if(id.equals(tournamentReservation))tournamentReservation=null;}
    private Snapshot startFrozen(String a,String b,UUID request,BotSubmission first,BotSubmission second,boolean secure) {
        if(closed)throw new IllegalStateException("Arena closed");
        for(Battle old:battles.values())if(old.request.equals(request)) {
            if(!old.a.equals(a)||!old.b.equals(b))throw new IllegalArgumentException("Request ID conflict");
            return old.snapshot;
        }
        if(active!=null && active.snapshot.status().equals("RUNNING"))throw new IllegalStateException("One battle at a time");
        // A fresh bounded execution cache per battle; game services stay shared.
        releaseRunner();
        if(battles.size()>=16)throw new IllegalStateException("History full; restart local arena");
        executions=new BotExecutionService(secure?isolated.matchScope():new TrustedQuickJsRunner(executable));
        coordinator=new BotMatchCoordinator(lobby,manager,rule,executions,secure?"VERIFIED_LOCAL_SANDBOX":TrustedQuickJsRunner.MODE);
        Battle battle=new Battle(a,b,request);
        boolean manual=tournamentReservation==null;
        battle.snapshot=project(null,a,b,"RUNNING",List.of(),null);
        var handle=coordinator.start(first,second,progress -> {
                synchronized(battle) {
                    if(battle.snapshot.result()==null)
                        battle.snapshot=project(battle.snapshot.id(),a,b,"RUNNING",progress.history(),null);
                }
                if(manual)snapshotObserver.accept(request,battle.snapshot);
            });
        synchronized(battle) {battle.snapshot=project(handle.matchId(),a,b,"RUNNING",battle.snapshot.moves(),null);}
        battles.put(handle.matchId(),battle);active=battle;
        handle.completion().whenComplete((summary,error)-> {
            synchronized(battle) {
                Result result;
                if(error!=null)result=new Result("CANCELLED",null,"INTERNAL_ERROR");
                else {
                    var r=summary.result();String winner=r.winners().contains(handle.seatA())?"A":r.winners().contains(handle.seatB())?"B":null;
                    boolean cancelled=!Set.of("GOAL_REACHED","PIECE_TYPE_ELIMINATED","NO_LEGAL_MOVES","MOVE_LIMIT_REACHED","FORFEIT").contains(r.reason());
                    result=new Result(cancelled?"CANCELLED":r.reason().equals("FORFEIT")?"FORFEIT":winner==null?"DRAW":"WIN",winner,r.reason());
                }
                battle.snapshot=project(handle.matchId(),a,b,result.outcome().equals("CANCELLED")?"CANCELLED":"FINISHED",
                    summary==null?battle.snapshot.moves():summary.history(),result);
            }
            if(manual)snapshotObserver.accept(request,battle.snapshot);
        });
        return battle.snapshot;
    }
    public synchronized Snapshot get(UUID id) {Battle b=battles.get(id);if(b==null)throw new NoSuchElementException("Battle not found");return b.snapshot;}
    private static Snapshot project(UUID id,String a,String b,String status,List<MoveEvent> moves,Result result) {
        var frames=new ArrayList<List<Piece>>();frames.add(Board.initial().pieces());
        for(int i=1;i<=moves.size();i++)frames.add(Board.replay(moves.subList(0,i)).pieces());
        String current=moves.isEmpty()?"A":moves.getLast().side().opponent().name();
        return new Snapshot(id,a,b,status,moves.size()+1,current,frames.getLast(),moves,frames,result);
    }
    private void releaseRunner() {
        try {if(coordinator!=null)coordinator.close();}finally {coordinator=null;if(executions!=null){executions.close();executions=null;}}
    }
    public synchronized void close() {
        if(closed)return;closed=true;
        try {releaseRunner();}finally {
            if(isolated!=null)isolated.close();
            manager.shutdown();try {manager.awaitTermination(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
        }
    }
}
