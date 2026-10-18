package dev.battlecode.botrunner;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static dev.battlecode.botrunner.BotExecutionFailure.Code.*;

/** Single local slot, four pending requests, bounded retained records; no runner I/O under service lock. */
public final class BotExecutionService implements BotRunner {
    private static final class Entry {
        final BotExecutionRequest request;
        final CompletableFuture<BotExecutionResult> result=new CompletableFuture<>();
        final AtomicBoolean cancelled=new AtomicBoolean();
        final CompletableFuture<Void> registered=new CompletableFuture<>();
        boolean starting;
        volatile long terminalAt;
        final long admittedAt=System.nanoTime();
        Entry(BotExecutionRequest request) { this.request=request; }
    }
    private final BotRunner delegate;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(4),Thread.ofPlatform().daemon(true).name("bot-execution-").factory(),new ThreadPoolExecutor.AbortPolicy());
    private final Map<UUID,Entry> records=new HashMap<>();
    private boolean closed;
    public BotExecutionService(BotRunner delegate) { ExecutionEnvironment.requireLocal(System.getenv());this.delegate=Objects.requireNonNull(delegate); }
    public CompletionStage<BotExecutionResult> execute(BotExecutionRequest request) {
        Entry entry;
        synchronized(this) {
            if(closed) return CompletableFuture.completedFuture(BotExecutionResult.failed(request.executionId(),RUNNER_UNAVAILABLE,"SERVICE_CLOSED"));
            // This local adapter retains IDs until close: bounded 256 records, enough for a 200-move demo.
            // A full service fails closed; no re-execution after eviction or durable restart guarantee.
            Entry old=records.get(request.executionId());
            if(old!=null) {
                if(!same(old.request,request)) return CompletableFuture.completedFuture(BotExecutionResult.failed(request.executionId(),INTERNAL_ERROR,"DUPLICATE_CONFLICT"));
                return old.result.minimalCompletionStage();
            }
            if(records.size()>=256) return CompletableFuture.completedFuture(BotExecutionResult.failed(request.executionId(),RUNNER_UNAVAILABLE,"RECORD_CAPACITY"));
            entry=new Entry(request); records.put(request.executionId(),entry);
            try { worker.execute(()->run(entry)); }
            catch(RejectedExecutionException e) { finish(entry,BotExecutionResult.failed(request.executionId(),RUNNER_UNAVAILABLE,"QUEUE_FULL")); }
        }
        return entry.result.minimalCompletionStage();
    }
    private boolean same(BotExecutionRequest a,BotExecutionRequest b) {
        return a.submission().submissionId().equals(b.submission().submissionId()) && a.submission().version()==b.submission().version()
            && a.submission().sourceSha256().equals(b.submission().sourceSha256()) && a.observation().equals(b.observation()) && a.limits().equals(b.limits());
    }
    private void run(Entry entry) {
        synchronized(entry) {if(entry.cancelled.get())return;entry.starting=true;}
        if(System.nanoTime()-entry.admittedAt>TimeUnit.SECONDS.toNanos(2)) {
            entry.registered.complete(null);
            finish(entry,BotExecutionResult.failed(entry.request.executionId(),RUNNER_UNAVAILABLE,"QUEUE_DEADLINE")); return;
        }
        CompletionStage<BotExecutionResult> pending;
        try {
            pending=delegate.execute(entry.request);
            entry.registered.complete(null);
            if(entry.cancelled.get()) delegate.cancel(entry.request.executionId()).toCompletableFuture().get(3,TimeUnit.SECONDS);
            BotExecutionResult result=pending.toCompletableFuture().get(6,TimeUnit.SECONDS);
            if(!entry.request.executionId().equals(result.executionId())) result=BotExecutionResult.failed(entry.request.executionId(),INTERNAL_ERROR,"CORRELATION_MISMATCH");
            finish(entry,result);
        } catch(TimeoutException e) {
            entry.registered.complete(null);
            delegate.cancel(entry.request.executionId());
            finish(entry,BotExecutionResult.failed(entry.request.executionId(),RUNNER_UNAVAILABLE,"SERVICE_DEADLINE"));
        } catch(InterruptedException e) {
            entry.registered.complete(null);
            Thread.currentThread().interrupt(); finish(entry,BotExecutionResult.failed(entry.request.executionId(),MATCH_CANCELLED,"SERVICE_INTERRUPTED"));
        } catch(Exception e) { entry.registered.complete(null);finish(entry,BotExecutionResult.failed(entry.request.executionId(),INTERNAL_ERROR,"DELEGATE_FAILURE")); }
    }
    private void finish(Entry entry,BotExecutionResult result) {
        synchronized(entry) {
            if(entry.cancelled.get()) result=BotExecutionResult.failed(entry.request.executionId(),MATCH_CANCELLED,"CANCELLED");
            if(entry.result.complete(result)) entry.terminalAt=System.nanoTime();
        }
    }
    public CompletionStage<Void> cancel(UUID id) {
        Entry entry;
        synchronized(this) { entry=records.get(id); }
        if(entry==null)return CompletableFuture.completedFuture(null);
        synchronized(entry) {
            if(entry.result.isDone())return CompletableFuture.completedFuture(null);entry.cancelled.set(true);
            if(!entry.starting) {finish(entry,BotExecutionResult.failed(id,MATCH_CANCELLED,"CANCELLED"));return CompletableFuture.completedFuture(null);}
        }
        // A cancel racing with delegate registration must await registration before killing the execution.
        return entry.registered.thenCompose(ignored->delegate.cancel(id)).thenRun(()->finish(entry,BotExecutionResult.failed(id,MATCH_CANCELLED,"CANCELLED")));
    }
    public int recordCount() { synchronized(this) { return records.size(); } }
    public String runtimeFingerprint() { return delegate.runtimeFingerprint(); }
    public int pendingCount() { return worker.getQueue().size(); }
    public void close() {
        List<UUID> ids;
        synchronized(this) { if(closed)return; closed=true; ids=List.copyOf(records.keySet()); }
        ids.forEach(id->cancel(id).toCompletableFuture().join());
        delegate.close(); worker.shutdownNow();
        try { if(!worker.awaitTermination(3,TimeUnit.SECONDS)) throw new IllegalStateException("Execution worker did not stop"); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
