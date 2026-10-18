package dev.battlecode.botmatch;

import dev.battlecode.botrunner.*;
import dev.battlecode.competition.*;
import dev.battlecode.engine.*;
import dev.battlecode.rulesapi.*;
import dev.battlecode.rulesapi.bot.*;
import dev.battlecode.rulesimpl.OttBoardRule;
import dev.battlecode.rulesimpl.board.MoveEvent;
import java.util.*;
import java.util.concurrent.*;

/** Local internal composition only. No submission endpoint and no TCP/WebSocket schema change. */
public final class BotMatchCoordinator implements AutoCloseable {
    public record Summary(UUID matchId, GameResult result, BotObservation finalObservation, List<MoveEvent> history,
                          String sourceHashA,String sourceHashB,String runtimeMode,
                          UUID submissionIdA,int versionA,UUID submissionIdB,int versionB,String runtimeFingerprint,BotExecutionLimits limits) {
        public Summary {history=List.copyOf(history);}
    }
    public record Progress(BotObservation observation, List<MoveEvent> history) { public Progress {history=List.copyOf(history);} }
    public record Handle(UUID matchId, UUID seatA, UUID seatB, CompletionStage<Summary> completion) { }
    private static final class Running {
        final UUID id,a,b;
        final BotSubmission first,second;
        final CompletableFuture<Summary> completion=new CompletableFuture<>();
        final java.util.concurrent.atomic.AtomicBoolean cancelled=new java.util.concurrent.atomic.AtomicBoolean();
        volatile UUID execution;
        java.util.function.Consumer<Progress> observer;
        Running(UUID id,UUID a,UUID b,BotSubmission first,BotSubmission second) {this.id=id;this.a=a;this.b=b;this.first=first;this.second=second;}
    }
    private final InMemoryLobby lobby;
    private final DefaultMatchManager manager;
    private final OttBoardRule rule;
    private final BotRunner runner;
    private final String runtimeMode;
    private final ExecutorService workers=Executors.newFixedThreadPool(4,Thread.ofPlatform().daemon(true).name("bot-match-").factory());
    private final Map<UUID,Running> running=new HashMap<>();
    private boolean closed;
    public BotMatchCoordinator(InMemoryLobby lobby,DefaultMatchManager manager,OttBoardRule rule,BotRunner runner,String runtimeMode) {
        this.lobby=Objects.requireNonNull(lobby);this.manager=Objects.requireNonNull(manager);this.rule=Objects.requireNonNull(rule);
        this.runner=Objects.requireNonNull(runner);this.runtimeMode=Objects.requireNonNull(runtimeMode);
    }
    public Handle start(BotSubmission first,BotSubmission second) {return start(first,second,progress -> {});}
    public synchronized Handle start(BotSubmission first,BotSubmission second,java.util.function.Consumer<Progress> observer) {
        if(closed || running.size()>=4) throw new IllegalStateException("Coordinator closed/full");
        Objects.requireNonNull(first);Objects.requireNonNull(second);
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        lobby.register(a,"Bot A");lobby.register(b,"Bot B");
        try {
            Room room=lobby.createRoom(a,OttBoardRule.ID,new GameConfig(2,200,10000,Map.of()));lobby.joinRoom(b,room.roomId());
            UUID id=manager.startBotMatch(a,room.roomId());
            Running match=new Running(id,a,b,first,second);match.observer=Objects.requireNonNull(observer);running.put(id,match);
            workers.submit(()->play(match));return new Handle(id,a,b,match.completion.minimalCompletionStage());
        } catch(RuntimeException e) {manager.disconnect(a);manager.disconnect(b);throw e;}
    }
    private void play(Running match) {
        GameState latest=null;
        BotObservation observation=null;
        List<MoveEvent> history=List.of();
        try {
            while(manager.result(match.id).isEmpty()) {
                Optional<BotMatchAccess.Lease> captured=manager.captureBotTurn(match.id,
                    (state,player)->rule.observation(state,player,BotExecutionLimits.LOCAL.wallTimeMs()));
                if(captured.isEmpty())break;
                var lease=captured.orElseThrow();observation=lease.observation();
                publish(match,new Progress(observation,history));
                if(match.cancelled.get())break;
                UUID execution=UUID.randomUUID();match.execution=execution;
                BotSubmission submission=lease.playerId().equals(match.a)?match.first:match.second;
                // No business locks held while waiting. Service/Runner bound setup, queue and guest separately.
                var pending=runner.execute(new BotExecutionRequest(execution,submission,observation,BotExecutionLimits.LOCAL));
                if(match.cancelled.get())runner.cancel(execution);
                BotExecutionResult result=pending.toCompletableFuture().get(8,TimeUnit.SECONDS);
                match.execution=null;
                if(!execution.equals(result.executionId())) {manager.cancelBotMatch(match.id,"BOT_CORRELATION_MISMATCH");break;}
                Optional<GameState> committed;
                if(result.failure()!=null) {
                    if(!result.failure().code().botCaused()) {manager.cancelBotMatch(match.id,result.failure().code().name());break;}
                    committed=manager.forfeitBotTurn(lease);
                } else {
                    BotAction action=result.action();
                    boolean legal=observation.legalMoves().stream().anyMatch(move->move.pieceId().equals(action.pieceId()) && move.to().equals(action.to()));
                    committed=legal?manager.commitBotTurn(lease,Map.of("pieceId",action.pieceId(),"to.row",Integer.toString(action.to().row()),"to.col",Integer.toString(action.to().col())))
                        :manager.forfeitBotTurn(lease);
                }
                if(committed.isEmpty()) {manager.cancelBotMatch(match.id,"STALE_BOT_LEASE");break;}
                latest=committed.orElseThrow();history=rule.history(latest.ruleState());
                observation=rule.observation(latest.ruleState(),lease.playerId(),BotExecutionLimits.LOCAL.wallTimeMs());
                publish(match,new Progress(rule.observation(latest.ruleState(),rule.currentPlayer(latest.ruleState()),BotExecutionLimits.LOCAL.wallTimeMs()),history));
            }
            GameResult result=manager.result(match.id).orElseThrow();
            match.completion.complete(new Summary(match.id,result,observation,history,match.first.sourceSha256(),match.second.sourceSha256(),runtimeMode,
                match.first.submissionId(),match.first.version(),match.second.submissionId(),match.second.version(),runner.runtimeFingerprint(),BotExecutionLimits.LOCAL));
        } catch(Exception e) {
            if(e instanceof InterruptedException)Thread.currentThread().interrupt();
            UUID execution=match.execution;if(execution!=null)runner.cancel(execution);
            manager.cancelBotMatch(match.id,"BOT_COORDINATOR_INFRASTRUCTURE");
            var result=manager.result(match.id);
            if(result.isPresent())match.completion.complete(new Summary(match.id,result.orElseThrow(),observation,history,
                match.first.sourceSha256(),match.second.sourceSha256(),runtimeMode,match.first.submissionId(),match.first.version(),match.second.submissionId(),match.second.version(),runner.runtimeFingerprint(),BotExecutionLimits.LOCAL));
            else match.completion.completeExceptionally(e);
        } finally {
            // Final room notifications/lifecycle already happened in manager. Remove only internal bot sessions.
            manager.disconnect(match.a);manager.disconnect(match.b);
            synchronized(this) {running.remove(match.id);}
        }
    }
    private static void publish(Running match,Progress progress) {
        try {match.observer.accept(progress);}catch(RuntimeException ignored) { /* A viewer cannot affect adjudication. */ }
    }
    public void cancel(UUID id) {
        Running match;synchronized(this){match=running.get(id);}
        if(match==null)return;
        match.cancelled.set(true);
        manager.cancelBotMatch(id,"MATCH_CANCELLED");UUID execution=match.execution;if(execution!=null)runner.cancel(execution);
    }
    public void close() {
        List<Running> matches;synchronized(this){if(closed)return;closed=true;matches=List.copyOf(running.values());}
        matches.forEach(match->cancel(match.id));workers.shutdownNow();
        try {if(!workers.awaitTermination(3,TimeUnit.SECONDS))throw new IllegalStateException("Bot coordinator did not stop");}
        catch(InterruptedException e){Thread.currentThread().interrupt();}
    }
}
