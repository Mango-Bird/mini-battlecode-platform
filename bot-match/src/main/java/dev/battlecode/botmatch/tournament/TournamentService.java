package dev.battlecode.botmatch.tournament;

import dev.battlecode.botmatch.LocalArenaService;
import dev.battlecode.botmatch.SubmissionRepository;
import dev.battlecode.botrunner.BotSubmission;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static dev.battlecode.botmatch.tournament.TournamentStatus.*;

/** One bounded orchestration thread; execution/admission belong to the existing Arena. */
public final class TournamentService implements AutoCloseable {
    public static final int MAX_TOURNAMENTS=4,MAX_REQUESTS=2048,MAX_ATTEMPTS=512;
    interface Execution {
        boolean eligible();void reserve(UUID id);void release(UUID id);
        LocalArenaService.Snapshot start(UUID id,BotSubmission a,BotSubmission b,UUID attempt);
        LocalArenaService.Snapshot get(UUID battle);void cancel(UUID id,UUID battle);void archive(UUID id,UUID battle);
    }
    private static final class Live {
        final UUID id,owner;final TournamentConfig config;final Instant created;
        TournamentStatus status=CREATED;String reason="";TournamentResult result;
        final Map<UUID,TournamentEntry> entries=new LinkedHashMap<>();
        final Map<UUID,BotSubmission> sources=new HashMap<>();
        final Map<UUID,TournamentMatch> matches=new LinkedHashMap<>();
        final Map<UUID,LocalArenaService.Snapshot> replay=new HashMap<>();
        Live(UUID owner,TournamentConfig config){this(UUID.randomUUID(),owner,config,Instant.now());}
        Live(UUID id,UUID owner,TournamentConfig config,Instant created){this.id=id;this.owner=owner;this.config=config;this.created=created;}
    }
    private record Memo(String fingerprint,Tournament response) {}
    private final Map<UUID,Live> tournaments=new LinkedHashMap<>();
    private final Map<String,Memo> requests=new HashMap<>();
    private final Execution execution;
    private final ThreadPoolExecutor scheduler=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1),
        Thread.ofPlatform().daemon(true).name("tournament-scheduler-").factory(),new ThreadPoolExecutor.AbortPolicy());
    private boolean closed;private int reservedAttempts;
    private TournamentRepository repository;private volatile boolean persistenceFailed;private UUID pendingStart;
    /** Existing execution graph, with a write-through DB projection and fail-closed restart recovery. */
    public TournamentService(LocalArenaService arena,TournamentRepository repository,SubmissionRepository submissions){
        this(arena);this.repository=Objects.requireNonNull(repository);
        try {
            for(var restored:repository.load()){
                var snapshot=restored.tournament();var t=new Live(snapshot.tournamentId(),snapshot.ownerId(),snapshot.config(),snapshot.createdAt());
                t.status=snapshot.status();t.reason=snapshot.schedulingReason();t.result=snapshot.result();
                snapshot.entries().forEach(e->{t.entries.put(e.entryId(),e);t.sources.put(e.entryId(),submissions.freezeVersion(e.botId(),e.version(),e.sourceSha256()));});
                snapshot.rounds().forEach(r->r.matches().forEach(m->t.matches.put(m.matchId(),m)));t.replay.putAll(restored.replay());
                if(!t.matches.isEmpty()||t.status==RUNNING){int n=t.entries.size();reservedAttempts+=t.config.format()==TournamentFormat.ROUND_ROBIN?n*(n-1):(n-1)*(t.config.maxDrawRematches()+1);}
                if(t.status==RUNNING||t.status==CANCELLED){if(t.status==RUNNING){t.status=CANCELLED;t.reason="BACKEND_RESTART_INTERRUPTED";}t.matches.replaceAll((key,m)->Set.of("RUNNING","PENDING").contains(m.status())?new TournamentMatch(m.matchId(),m.round(),m.slot(),m.attempt(),m.entryA(),m.entryB(),"CANCELLED",m.battleId(),null,null,t.reason):m);t.result=new TournamentResult(null,t.reason,standings(t));}
                tournaments.put(t.id,t);
            }
            checkpoint(null);
        }catch(RuntimeException e){scheduler.shutdownNow();throw e;}
    }
    private void available(){if(persistenceFailed)throw new IllegalStateException("Persistence failed; restart required");if(repository!=null)repository.checkAvailable();}
    private void checkpoint(TournamentRepository.Mutation mutation){if(repository==null)return;
        try{repository.save(tournaments.values().stream().map(t->new TournamentRepository.Restored(view(t),t.replay)).toList(),mutation);}
        catch(RuntimeException e){persistenceFailed=true;throw e;}
    }
    private void expire(){boolean changed=false;for(var t:tournaments.values())if(t.status==REGISTRATION_OPEN&&t.config.registrationDeadline()!=null&&!t.config.registrationDeadline().isAfter(Instant.now())){t.status=REGISTRATION_CLOSED;changed=true;}if(changed)checkpoint(null);}
    public TournamentService(LocalArenaService arena) {this(new Execution(){
        public boolean eligible(){return arena.userBotsExecutable();}
        public void reserve(UUID id){arena.reserveTournament(id);}public void release(UUID id){arena.releaseTournament(id);}
        public LocalArenaService.Snapshot start(UUID id,BotSubmission a,BotSubmission b,UUID attempt){return arena.startTournamentBattle(id,a,b,attempt);}
        public LocalArenaService.Snapshot get(UUID battle){return arena.get(battle);}
        public void cancel(UUID id,UUID battle){arena.cancelTournamentBattle(id,battle);}
        public void archive(UUID id,UUID battle){arena.archiveTournamentBattle(id,battle);}
    });}
    TournamentService(Execution execution){this.execution=execution;}
    private Live live(UUID id){Live t=tournaments.get(id);if(t==null)throw new NoSuchElementException("Tournament");return t;}
    private void admin(Live t,UUID owner){if(!t.owner.equals(owner))throw new SecurityException("Tournament owner required");}
    private void gate(){if(!execution.eligible())throw new IllegalStateException("Live sandbox gate unavailable");}
    private void state(Live t,TournamentStatus status){if(t.status!=status)throw new IllegalStateException("Tournament transition");}
    private Tournament mutate(UUID owner,UUID request,String fingerprint,Supplier<Tournament> action) {
        available();expire();
        if(closed)throw new IllegalStateException("Tournament service closed");
        String key=owner+":"+request;Memo memo=requests.get(key);
        if(memo!=null){if(!memo.fingerprint.equals(fingerprint))throw new IllegalArgumentException("Request ID conflict");return memo.response;}
        if(repository!=null){Tournament previous=repository.previous(owner,request,fingerprint);if(previous!=null)return previous;}
        if(requests.size()>=MAX_REQUESTS)throw new IllegalStateException("Request capacity");
        Tournament response;
        try{response=action.get();checkpoint(new TournamentRepository.Mutation(owner,request,fingerprint,response));}
        catch(RuntimeException e){if(pendingStart!=null){execution.release(pendingStart);pendingStart=null;}throw e;}
        requests.put(key,new Memo(fingerprint,response));
        if(pendingStart!=null){UUID id=pendingStart;pendingStart=null;try{scheduler.execute(()->run(id));}catch(RejectedExecutionException e){interrupt(id,"SCHEDULER_UNAVAILABLE");execution.release(id);}}
        return response;
    }
    public synchronized Tournament create(UUID owner,UUID request,TournamentConfig config){return mutate(owner,request,"CREATE:"+config,()->{
        if(tournaments.size()>=MAX_TOURNAMENTS)throw new IllegalStateException("Tournament capacity");
        if(config.registrationDeadline()!=null&&!config.registrationDeadline().isAfter(Instant.now()))throw new IllegalArgumentException("Deadline in past");
        Live t=new Live(owner,config);tournaments.put(t.id,t);return view(t);
    });}
    public synchronized List<Tournament> list(){available();expire();return tournaments.values().stream().map(this::view).toList();}
    public synchronized Tournament get(UUID id){available();expire();return view(live(id));}
    public synchronized Tournament transition(UUID owner,UUID request,UUID id,String operation){return mutate(owner,request,operation+":"+id,()->{
        Live t=live(id);admin(t,owner);
        switch(operation){
            case "open" -> {state(t,CREATED);if(t.config.registrationDeadline()!=null&&!t.config.registrationDeadline().isAfter(Instant.now()))throw new IllegalStateException("Deadline expired");t.status=REGISTRATION_OPEN;}
            case "close" -> {state(t,REGISTRATION_OPEN);t.status=REGISTRATION_CLOSED;}
            case "start" -> {
                state(t,REGISTRATION_CLOSED);gate();if(t.entries.size()<2)throw new IllegalStateException("At least two entries");
                int n=t.entries.size(),budget=t.config.format()==TournamentFormat.ROUND_ROBIN?n*(n-1):(n-1)*(t.config.maxDrawRematches()+1);
                if(reservedAttempts+budget>MAX_ATTEMPTS)throw new IllegalStateException("Global match history capacity");
                execution.reserve(t.id);reservedAttempts+=budget;
                var order=TournamentRules.seeded(List.copyOf(t.entries.values()),t.config.seed());
                for(int i=0;i<order.size();i++){var old=t.entries.get(order.get(i));t.entries.put(old.entryId(),new TournamentEntry(old.entryId(),old.ownerId(),old.ownerDisplay(),old.botId(),old.botName(),old.version(),old.sourceSha256(),i));}
                t.status=RUNNING;t.reason="";
                var scheduled=t.config.format()==TournamentFormat.ROUND_ROBIN?TournamentRules.roundRobin(t.id,order):TournamentRules.elimination(t.id,1,order);
                scheduled.forEach(m->t.matches.putIfAbsent(m.matchId(),m));pendingStart=t.id;
            }
            case "cancel" -> {if(t.status==FINISHED||t.status==CANCELLED)throw new IllegalStateException("Terminal tournament");t.status=CANCELLED;t.reason="OWNER_CANCELLED";t.result=new TournamentResult(null,t.reason,standings(t));}
            default -> throw new IllegalArgumentException("Operation");
        }
        return view(t);
    });}
    public synchronized Tournament register(UUID owner,UUID request,UUID id,String reference,String name,Supplier<BotSubmission> freeze) {
        return register(owner,request,id,reference,()->name,freeze);
    }
    public synchronized Tournament register(UUID owner,UUID request,UUID id,String reference,Supplier<String> name,Supplier<BotSubmission> freeze) {
        return mutate(owner,request,"REGISTER:"+id+":"+reference,()->{
            Live t=live(id);registration(t);gate();
            if(t.entries.size()>=t.config.maxParticipants()||t.entries.values().stream().anyMatch(e->e.ownerId().equals(owner)))throw new IllegalStateException("Registration duplicate/full");
            BotSubmission source=freeze.get();UUID entry=UUID.randomUUID();
            t.entries.put(entry,new TournamentEntry(entry,owner,"Owner-"+owner.toString().substring(0,8),source.submissionId(),name.get(),source.version(),source.sourceSha256(),t.entries.size()));
            t.sources.put(entry,source);return view(t);
        });
    }
    public synchronized Tournament withdraw(UUID owner,UUID request,UUID id){return mutate(owner,request,"WITHDRAW:"+id,()->{
        Live t=live(id);registration(t);var e=t.entries.values().stream().filter(v->v.ownerId().equals(owner)).findFirst().orElseThrow();t.entries.remove(e.entryId());t.sources.remove(e.entryId());return view(t);
    });}
    private void registration(Live t){state(t,REGISTRATION_OPEN);if(t.config.registrationDeadline()!=null&&!t.config.registrationDeadline().isAfter(Instant.now())){t.status=REGISTRATION_CLOSED;throw new IllegalStateException("Registration deadline expired");}}
    private List<TournamentStanding> standings(Live t){return TournamentRules.standings(List.copyOf(t.entries.values()),List.copyOf(t.matches.values()));}
    private Tournament view(Live t){
        if(t.status==REGISTRATION_OPEN&&t.config.registrationDeadline()!=null&&!t.config.registrationDeadline().isAfter(Instant.now()))t.status=REGISTRATION_CLOSED;
        if(t.status!=RUNNING&&t.status!=FINISHED&&t.status!=CANCELLED){
            var seedOrder=TournamentRules.seeded(List.copyOf(t.entries.values()),t.config.seed());
            for(int i=0;i<seedOrder.size();i++){var e=t.entries.get(seedOrder.get(i));t.entries.put(e.entryId(),new TournamentEntry(e.entryId(),e.ownerId(),e.ownerDisplay(),e.botId(),e.botName(),e.version(),e.sourceSha256(),i));}
        }
        Map<Integer,List<TournamentMatch>> rounds=new TreeMap<>();t.matches.values().forEach(m->rounds.computeIfAbsent(m.round(),r->new ArrayList<>()).add(m));
        var roundViews=rounds.entrySet().stream().map(e->new TournamentRound(e.getKey(),e.getValue(),e.getValue().stream()
            .filter(m->m.winnerId()!=null&&!m.status().equals("DRAW_REPLAY")).map(TournamentMatch::winnerId).distinct().toList())).toList();
        return new Tournament(t.id,t.config,t.created,t.owner,t.status,List.copyOf(t.entries.values()),roundViews,standings(t),t.result,t.reason);
    }
    public LocalArenaService.Snapshot matchSnapshot(UUID id,UUID matchId) {
        available();
        UUID battle;
        synchronized(this){Live t=live(id);if(t.replay.containsKey(matchId))return t.replay.get(matchId);var m=t.matches.get(matchId);if(m==null)throw new NoSuchElementException("Match");battle=m.battleId();}
        if(battle==null)throw new IllegalStateException("No executed battle (pending/BYE)");
        try{return execution.get(battle);}catch(NoSuchElementException e){synchronized(this){var snapshot=live(id).replay.get(matchId);if(snapshot!=null)return snapshot;throw e;}}
    }
    private void run(UUID id) {
        try {
            List<UUID> order;TournamentFormat format;
            synchronized(this){Live t=live(id);order=t.entries.values().stream().sorted(Comparator.comparingInt(TournamentEntry::seedOrder)).map(TournamentEntry::entryId).toList();format=t.config.format();}
            if(format==TournamentFormat.ROUND_ROBIN){var matches=TournamentRules.roundRobin(id,order);add(id,matches);for(var m:matches)if(play(id,m)==null)return;finish(id,null,"ROUND_ROBIN_COMPLETE");}
            else {
                int round=1;boolean tied=false;
                while(order.size()>1){var matches=TournamentRules.elimination(id,round++,order);add(id,matches);var next=new ArrayList<UUID>();
                    for(var original:matches){if(original.entryB()==null){next.add(original.entryA());continue;}
                        TournamentMatch m=original;UUID winner;
                        while(true){var result=play(id,m);if(result==null)return;
                            if(result.winnerId()!=null){winner=result.winnerId();break;}
                            int max;synchronized(this){max=live(id).config.maxDrawRematches();}
                            if(m.attempt()<max){synchronized(this){Live t=live(id);t.matches.put(m.matchId(),new TournamentMatch(m.matchId(),m.round(),m.slot(),m.attempt(),m.entryA(),m.entryB(),"DRAW_REPLAY",result.battleId(),null,result.result()));checkpoint(null);}
                                m=TournamentRules.match(id,m.round(),m.slot(),m.attempt()+1,m.entryB(),m.entryA());add(id,List.of(m));}
                            else {synchronized(this){Live t=live(id);winner=t.entries.get(m.entryA()).seedOrder()<t.entries.get(m.entryB()).seedOrder()?m.entryA():m.entryB();
                                t.matches.put(m.matchId(),new TournamentMatch(m.matchId(),m.round(),m.slot(),m.attempt(),m.entryA(),m.entryB(),"FINISHED",result.battleId(),winner,result.result()));checkpoint(null);}tied=true;break;}
                        }
                        next.add(winner);
                    }
                    order=List.copyOf(next);
                }
                finish(id,order.getFirst(),tied?"ELIMINATION_COMPLETE_WITH_SEED_TIEBREAK":"ELIMINATION_COMPLETE");
            }
        }catch(Exception e){interrupt(id,"INFRASTRUCTURE_OR_SCHEDULER_ERROR");}
        finally{
            synchronized(this){Live t=live(id);if(t.status==CANCELLED){t.matches.replaceAll((key,m)->Set.of("RUNNING","PENDING").contains(m.status())?new TournamentMatch(m.matchId(),m.round(),m.slot(),m.attempt(),m.entryA(),m.entryB(),"CANCELLED",m.battleId(),null,null,t.reason):m);t.result=new TournamentResult(null,t.reason,standings(t));}}
            try{synchronized(this){if(!persistenceFailed)checkpoint(null);}}finally{
                if(persistenceFailed){List<UUID> battles;synchronized(this){battles=live(id).matches.values().stream().filter(m->m.battleId()!=null).map(TournamentMatch::battleId).toList();}battles.forEach(b->execution.cancel(id,b));}
                execution.release(id);
            }
        }
    }
    private synchronized void add(UUID id,List<TournamentMatch> matches){available();Live t=live(id);if(t.status==RUNNING){matches.forEach(m->t.matches.putIfAbsent(m.matchId(),m));checkpoint(null);}}
    private TournamentMatch play(UUID id,TournamentMatch match) throws InterruptedException {
        BotSubmission a,b;
        synchronized(this){available();Live t=live(id);if(closed||t.status!=RUNNING)return null;a=t.sources.get(match.entryA());b=t.sources.get(match.entryB());t.matches.put(match.matchId(),new TournamentMatch(match.matchId(),match.round(),match.slot(),match.attempt(),match.entryA(),match.entryB(),"RUNNING",null,null,null));checkpoint(null);}
        LocalArenaService.Snapshot snapshot;
        try{snapshot=execution.start(id,a,b,match.matchId());}catch(Exception e){interrupt(id,"RUNNER_OR_ADMISSION_UNAVAILABLE");return null;}
        UUID battle=snapshot.id();boolean cancelled=false;
        synchronized(this){Live t=live(id);t.matches.put(match.matchId(),new TournamentMatch(match.matchId(),match.round(),match.slot(),match.attempt(),match.entryA(),match.entryB(),"RUNNING",battle,null,null));checkpoint(null);}
        long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(8);
        long cancellationDeadline=Long.MAX_VALUE;
        try {
            while(snapshot.status().equals("RUNNING")) {
                synchronized(this){available();cancelled=closed||live(id).status!=RUNNING;}
                if(cancelled||System.nanoTime()>deadline){
                    if(cancellationDeadline==Long.MAX_VALUE){execution.cancel(id,battle);cancellationDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);}
                    cancelled=true;
                    if(System.nanoTime()>cancellationDeadline){interrupt(id,"CANCELLATION_UNCONFIRMED");return null;}
                }
                Thread.sleep(50);snapshot=execution.get(battle);
            }
        }catch(InterruptedException e){execution.cancel(id,battle);throw e;}
        synchronized(this){cancelled|=closed||live(id).status!=RUNNING;}
        UUID winner=snapshot.result().winner()==null?null:snapshot.result().winner().equals("A")?match.entryA():match.entryB();
        String status=cancelled||snapshot.status().equals("CANCELLED")?"CANCELLED":"FINISHED";
        var result=new TournamentMatch(match.matchId(),match.round(),match.slot(),match.attempt(),match.entryA(),match.entryB(),status,battle,status.equals("CANCELLED")?null:winner,snapshot.result());
        synchronized(this){Live t=live(id);t.matches.put(match.matchId(),result);t.replay.put(match.matchId(),snapshot);checkpoint(null);}
        execution.archive(id,battle);
        if(status.equals("CANCELLED")){interrupt(id,"MATCH_INTERRUPTED:"+snapshot.result().reason());return null;}
        return result;
    }
    private synchronized void interrupt(UUID id,String reason){Live t=live(id);
        t.matches.replaceAll((key,m)->Set.of("RUNNING","PENDING").contains(m.status())?new TournamentMatch(m.matchId(),m.round(),m.slot(),m.attempt(),m.entryA(),m.entryB(),"CANCELLED",m.battleId(),null,null,reason):m);
        if(t.status==RUNNING){t.status=CANCELLED;t.reason=reason;t.result=new TournamentResult(null,reason,standings(t));}if(!persistenceFailed)checkpoint(null);}
    private synchronized void finish(UUID id,UUID champion,String reason){Live t=live(id);if(t.status==RUNNING){t.status=FINISHED;t.reason=reason;var standings=standings(t);t.result=new TournamentResult(champion==null?standings.getFirst().entryId():champion,reason,standings);checkpoint(null);}}
    public void close() {
        synchronized(this){if(closed)return;closed=true;tournaments.values().stream().filter(t->repository==null?t.status!=FINISHED&&t.status!=CANCELLED:t.status==RUNNING).forEach(t->{t.status=CANCELLED;t.reason="SERVER_SHUTDOWN";t.result=new TournamentResult(null,t.reason,standings(t));});try{if(!persistenceFailed)checkpoint(null);}catch(RuntimeException ignored){/* Restart recovery will cancel the last committed RUNNING state. */}}
        scheduler.shutdown();try{if(!scheduler.awaitTermination(12,TimeUnit.SECONDS))scheduler.shutdownNow();}catch(InterruptedException e){scheduler.shutdownNow();Thread.currentThread().interrupt();}
    }
}
