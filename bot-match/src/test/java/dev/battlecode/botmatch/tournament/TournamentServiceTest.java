package dev.battlecode.botmatch.tournament;
import dev.battlecode.botmatch.LocalArenaService;
import dev.battlecode.botrunner.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Controlled execution-port tests for orchestration, not a substitute for real sandbox IT. */
@Timeout(15)
class TournamentServiceTest {
    static class Port implements TournamentService.Execution {
        volatile boolean eligible=true,held,block;String outcome="FORFEIT";
        final Map<UUID,LocalArenaService.Snapshot> snapshots=new ConcurrentHashMap<>();
        final List<BotSubmission> sources=new CopyOnWriteArrayList<>();int calls;
        public boolean eligible(){return eligible;}
        public synchronized void reserve(UUID id){if(held)throw new IllegalStateException("Busy");held=true;}
        public void release(UUID id){held=false;}
        public synchronized LocalArenaService.Snapshot start(UUID id,BotSubmission a,BotSubmission b,UUID attempt){sources.add(a);sources.add(b);calls++;UUID battle=UUID.randomUUID();var s=snapshot(battle,block?"RUNNING":outcome);snapshots.put(battle,s);return s;}
        LocalArenaService.Snapshot snapshot(UUID id,String status){boolean running=status.equals("RUNNING");return new LocalArenaService.Snapshot(id,"a","b",running?"RUNNING":status.equals("CANCELLED")?"CANCELLED":"FINISHED",1,"A",List.of(),List.of(),List.of(List.of()),running?null:new LocalArenaService.Result(status,Set.of("DRAW","CANCELLED").contains(status)?null:"A",status.equals("CANCELLED")?"RUNNER_CRASH":status));}
        public LocalArenaService.Snapshot get(UUID id){return snapshots.get(id);}
        public void cancel(UUID t,UUID b){snapshots.put(b,snapshot(b,"CANCELLED"));}
        public void archive(UUID t,UUID b){}
    }
    static BotSubmission source(){byte[] bytes="function chooseMove(o){return {}; }".getBytes(StandardCharsets.UTF_8);return new BotSubmission(UUID.randomUUID(),1,bytes,BotSubmission.hash(bytes));}
    static UUID request(){return UUID.randomUUID();}
    Tournament setup(TournamentService service,UUID owner,TournamentFormat format,int count,int rematches) {
        var t=service.create(owner,request(),new TournamentConfig("Test",format,16,null,42,rematches));service.transition(owner,request(),t.tournamentId(),"open");
        for(int i=0;i<count;i++){var saved=source();service.register(UUID.randomUUID(),request(),t.tournamentId(),saved.submissionId()+":1:"+saved.sourceSha256(),"Bot "+i,()->saved);}
        service.transition(owner,request(),t.tournamentId(),"close");return service.get(t.tournamentId());
    }
    Tournament await(TournamentService service,UUID id) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);Tournament t;
        do{t=service.get(id);if(t.status()!=TournamentStatus.RUNNING)return t;Thread.sleep(10);}while(System.nanoTime()<until);
        fail("Finite deadline expired");return null;
    }
    @ParameterizedTest @ValueSource(ints={2,3,4}) void roundRobinCountsBothSidesNoSelfAndScores(int count) throws Exception {
        var port=new Port();try(var service=new TournamentService(port)){
            UUID owner=request();var t=setup(service,owner,TournamentFormat.ROUND_ROBIN,count,1);service.transition(owner,request(),t.tournamentId(),"start");t=await(service,t.tournamentId());
            assertEquals(TournamentStatus.FINISHED,t.status());var matches=t.rounds().stream().flatMap(r->r.matches().stream()).toList();
            assertEquals(count*(count-1),matches.size());assertEquals(matches.size(),new HashSet<>(matches.stream().map(TournamentMatch::matchId).toList()).size());
            assertTrue(matches.stream().noneMatch(m->m.entryA().equals(m.entryB())));
            for(var e:t.entries()){assertEquals(count-1,matches.stream().filter(m->m.entryA().equals(e.entryId())).count());assertEquals(count-1,matches.stream().filter(m->m.entryB().equals(e.entryId())).count());}
            for(var s:t.standings()){assertEquals(2*(count-1),s.played());assertEquals(count-1,s.wins());assertEquals(count-1,s.losses());assertEquals(count-1,s.forfeits());assertEquals(3*(count-1),s.points());}
        }
    }
    @ParameterizedTest @ValueSource(ints={3,4,5}) void eliminationHasByesAndNoFutureRoundBeforeCompletion(int count) throws Exception {
        var port=new Port();try(var service=new TournamentService(port)){
            UUID owner=request();var t=setup(service,owner,TournamentFormat.SINGLE_ELIMINATION,count,0);service.transition(owner,request(),t.tournamentId(),"start");t=await(service,t.tournamentId());
            assertEquals(count-1,port.calls);assertNotNull(t.result().championId());
            long byes=t.rounds().stream().flatMap(r->r.matches().stream()).filter(m->m.status().equals("BYE")).count();assertEquals(count==4?0:count==3?1:3,byes);
        }
    }
    @Test void drawRematchesSwapSidesAndPublishedSeedBreaksTie() throws Exception {
        var port=new Port();port.outcome="DRAW";
        try(var service=new TournamentService(port)){UUID owner=request();var t=setup(service,owner,TournamentFormat.SINGLE_ELIMINATION,2,2);var best=t.entries().stream().min(Comparator.comparingInt(TournamentEntry::seedOrder)).orElseThrow();
            service.transition(owner,request(),t.tournamentId(),"start");t=await(service,t.tournamentId());var matches=t.rounds().getFirst().matches();assertEquals(3,matches.size());
            assertEquals(matches.get(0).entryA(),matches.get(1).entryB());assertEquals(best.entryId(),t.result().championId());assertTrue(t.result().reason().contains("SEED_TIEBREAK"));}
    }
    @Test void infrastructureCancelsWithoutScoresAndNoFurtherRounds() throws Exception {
        var port=new Port();port.outcome="CANCELLED";try(var service=new TournamentService(port)){UUID owner=request();var t=setup(service,owner,TournamentFormat.SINGLE_ELIMINATION,4,1);service.transition(owner,request(),t.tournamentId(),"start");t=await(service,t.tournamentId());
            assertEquals(TournamentStatus.CANCELLED,t.status());assertNull(t.result().championId());assertEquals(1,port.calls);assertEquals(1,t.rounds().size());assertTrue(t.standings().stream().allMatch(s->s.points()==0&&s.played()==0));assertTrue(t.rounds().getFirst().matches().stream().allMatch(m->!m.interruptionReason().isBlank()));}
    }
    @Test void idempotencyOwnershipConcurrentRegistrationAndAdmission() throws Exception {
        var port=new Port();port.block=true;try(var service=new TournamentService(port);var threads=Executors.newFixedThreadPool(4)) {
            UUID owner=request(),id=request();var config=new TournamentConfig("Atomic",TournamentFormat.ROUND_ROBIN,4,null,7,1);var t=service.create(owner,id,config);
            assertEquals(t.tournamentId(),service.create(owner,id,config).tournamentId());assertThrows(IllegalArgumentException.class,()->service.create(owner,id,new TournamentConfig("Other",TournamentFormat.ROUND_ROBIN,4,null,7,1)));
            UUID tournament=t.tournamentId();assertThrows(SecurityException.class,()->service.transition(request(),request(),tournament,"open"));service.transition(owner,request(),tournament,"open");
            UUID participant=request();var saved=source();var futures=new ArrayList<Future<Boolean>>();for(int i=0;i<4;i++)futures.add(threads.submit(()->{try{service.register(participant,request(),tournament,"ref","Bot",()->saved);return true;}catch(IllegalStateException e){return false;}}));
            int accepted=0;for(var f:futures)if(f.get(2,TimeUnit.SECONDS))accepted++;assertEquals(1,accepted);
            var second=source();service.register(request(),request(),tournament,"two","Two",()->second);service.transition(owner,request(),tournament,"close");
            var starts=new ArrayList<Future<Boolean>>();for(int i=0;i<4;i++)starts.add(threads.submit(()->{try{service.transition(owner,request(),tournament,"start");return true;}catch(IllegalStateException e){return false;}}));
            accepted=0;for(var f:starts)if(f.get(2,TimeUnit.SECONDS))accepted++;assertEquals(1,accepted);assertTrue(port.held);
            assertThrows(IllegalStateException.class,()->service.withdraw(participant,request(),tournament));
            service.close();assertEquals(TournamentStatus.CANCELLED,service.get(tournament).status());assertFalse(port.held);
        }
    }
    @Test void missingLiveGateBlocksRegistration() {
        var port=new Port();port.eligible=false;try(var service=new TournamentService(port)){var t=service.create(request(),request(),new TournamentConfig("Gate",TournamentFormat.ROUND_ROBIN,2,null,1,0));service.transition(t.ownerId(),request(),t.tournamentId(),"open");assertThrows(IllegalStateException.class,()->service.register(request(),request(),t.tournamentId(),"ref","Bot",TournamentServiceTest::source));}
    }
    @Test void headToHeadIsUsedBeforeSeedOrder() {
        UUID a=request(),b=request(),c=request(),d=request();var entries=List.of(
            new TournamentEntry(a,a,"A",a,"A",1,"hash",1),new TournamentEntry(b,b,"B",b,"B",1,"hash",0),
            new TournamentEntry(c,c,"C",c,"C",1,"hash",2),new TournamentEntry(d,d,"D",d,"D",1,"hash",3));
        var matches=new ArrayList<TournamentMatch>();
        for(var pair:List.of(List.of(a,b),List.of(c,a),List.of(b,d),List.of(c,d)))matches.add(new TournamentMatch(request(),1,0,0,pair.get(0),pair.get(1),"FINISHED",request(),pair.get(0),new LocalArenaService.Result("WIN","A","GOAL_REACHED")));
        var standings=TournamentRules.standings(entries,matches);assertEquals(c,standings.get(0).entryId());assertEquals(a,standings.get(1).entryId());assertEquals(b,standings.get(2).entryId());
    }
    @Test void deadlinesWithdrawAndFrozenBytesAreIndependentOfLaterEdits() throws Exception {
        var port=new Port();try(var service=new TournamentService(port)){
            UUID owner=request();var t=service.create(owner,request(),new TournamentConfig("Deadline",TournamentFormat.ROUND_ROBIN,2,java.time.Instant.now().plusMillis(1000),1,0));
            service.transition(owner,request(),t.tournamentId(),"open");var saved=source();UUID participant=request();service.register(participant,request(),t.tournamentId(),"ref","Bot",()->saved);
            service.withdraw(participant,request(),t.tournamentId());assertTrue(service.get(t.tournamentId()).entries().isEmpty());Thread.sleep(1010);assertEquals(TournamentStatus.REGISTRATION_CLOSED,service.get(t.tournamentId()).status());
            var frozen=setup(service,owner,TournamentFormat.ROUND_ROBIN,2,0);service.transition(owner,request(),frozen.tournamentId(),"start");await(service,frozen.tournamentId());
            for(var bytes:port.sources){assertEquals(1,bytes.version());assertEquals(BotSubmission.hash(bytes.source()),bytes.sourceSha256());byte[] copy=bytes.source();copy[0]=0;assertNotEquals(0,bytes.source()[0]);}
        }
    }
}
