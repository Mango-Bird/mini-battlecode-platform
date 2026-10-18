package dev.battlecode.botmatch.ranked;
import dev.battlecode.botmatch.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One periodic task, one thread; uses existing Arena/VerifiedWorkerRunner execution graph. */
public final class RankedMatchCoordinator implements AutoCloseable {
    private final LocalArenaService arena;private final SubmissionRepository sources;private final MatchmakingService service;
    private final ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).name("ranked-coordinator-").factory());
    private final AtomicBoolean closed=new AtomicBoolean();private volatile UUID reservation,battle;private volatile boolean failed;
    public RankedMatchCoordinator(LocalArenaService arena,SubmissionRepository sources,MatchmakingService service){this.arena=arena;this.sources=sources;this.service=service;service.recover();scheduler.scheduleWithFixedDelay(this::tick,1,1,TimeUnit.SECONDS);}
    private void tick(){if(closed.get()||failed||!arena.userBotsExecutable())return;try{if(!service.hasWaitingPair())return;}catch(RuntimeException unavailable){failed=true;return;}UUID lease=UUID.randomUUID();
        try{arena.reserveRanked(lease);}catch(IllegalStateException unavailable){return;}
        reservation=lease;
        try{
            var pair=service.reserve();if(pair==null)return;
            for(int n=1;n<=2;n++){
                if(closed.get())throw new InterruptedException();
                var a=n==1?pair.a():pair.b();var b=n==1?pair.b():pair.a();
                var snapshot=arena.startRankedBattle(lease,sources.freezeVersion(a.botId(),a.version(),a.sourceSha256()),sources.freezeVersion(b.botId(),b.version(),b.sourceSha256()),MatchmakingService.gameId(pair.pairingId(),n));
                battle=snapshot.id();service.running(pair,n,battle);long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(10);
                while(snapshot.status().equals("RUNNING")){
                    if(closed.get()||System.nanoTime()>deadline)throw new InterruptedException();service.progress(MatchmakingService.gameId(pair.pairingId(),n),snapshot);Thread.sleep(200);snapshot=arena.get(battle);
                }
                if(!snapshot.status().equals("FINISHED")){service.recover();return;}
                service.complete(pair,n,snapshot);arena.archiveRanked(lease,battle);battle=null;
            }
        }catch(InterruptedException e){if(battle!=null)arena.cancelRanked(lease,battle);try{service.recover();}catch(RuntimeException failure){failed=true;}Thread.currentThread().interrupt();}
        catch(RuntimeException e){if(battle!=null)arena.cancelRanked(lease,battle);failed=true;try{service.recover();}catch(RuntimeException ignored){/* Startup recovery handles last committed intent. */}}
        finally{if(battle!=null)try{arena.archiveRanked(lease,battle);}catch(RuntimeException ignored){}battle=null;reservation=null;arena.releaseRanked(lease);}
    }
    public void close(){if(!closed.compareAndSet(false,true))return;UUID r=reservation,b=battle;if(r!=null&&b!=null)arena.cancelRanked(r,b);scheduler.shutdownNow();try{scheduler.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}
