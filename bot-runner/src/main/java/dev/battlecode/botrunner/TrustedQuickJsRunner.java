package dev.battlecode.botrunner;

import com.fasterxml.jackson.databind.*;
import dev.battlecode.rulesapi.bot.*;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static dev.battlecode.botrunner.BotExecutionFailure.Code.*;

/** TRUSTED_FIXTURES_ONLY: fresh external QuickJS. This is explicitly NOT a secure sandbox. */
public final class TrustedQuickJsRunner implements BotRunner {
    public static final String MODE="TRUSTED_FIXTURES_ONLY";
    public static final String ARTIFACT_SHA256="7b27412de844403545bd151fbe49191b4d5b91a9e15b5db7c863fea54639a82b";
    private final Path executable;
    private final boolean artifactVerified;
    private final ExecutorService tasks=Executors.newCachedThreadPool(Thread.ofPlatform().daemon(true).name("quickjs-control-").factory());
    private final Map<UUID,Job> jobs=new HashMap<>();
    private final AtomicInteger liveProcesses=new AtomicInteger();
    private boolean closed;
    private volatile boolean quarantined;
    private static final class Job {
        final AtomicBoolean cancelled=new AtomicBoolean();
        final CompletableFuture<BotExecutionResult> result=new CompletableFuture<>();
        volatile Process process;
    }
    private static final class Outcome extends Exception {
        final BotExecutionResult result;
        Outcome(BotExecutionResult result) { this.result=result; }
    }
    public TrustedQuickJsRunner(Path executable) {
        ExecutionEnvironment.requireLocal(System.getenv());
        this.executable=Objects.requireNonNull(executable).toAbsolutePath();
        boolean verified=false;
        try {verified=Files.isRegularFile(this.executable) && ARTIFACT_SHA256.equals(BotSubmission.hash(Files.readAllBytes(this.executable)));}
        catch(IOException ignored) { }
        artifactVerified=verified;
    }
    public String runtimeFingerprint() {return artifactVerified?"QuickJS-NG/0.16.2/windows-x64/"+ARTIFACT_SHA256:"UNVERIFIED_QUICKJS";}
    public Map<String,Boolean> capabilities() {
        return Map.of("arbitrarySource",false,"osIsolationVerified",false,"wallKill",true,
            "quickJsHeapFlag",artifactVerified,"quickJsStackFlag",artifactVerified,"cpuQuota",false,"processMemoryQuota",false);
    }
    public CompletionStage<BotExecutionResult> execute(BotExecutionRequest request) {
        if(!TrustedBotFixtures.allows(request.submission())) return CompletableFuture.completedFuture(
            BotExecutionResult.failed(request.executionId(),RUNNER_UNAVAILABLE,"TRUSTED_FIXTURES_ONLY"));
        Job job=new Job();
        synchronized(this) {
            if(closed || quarantined || !artifactVerified || !Files.isRegularFile(executable)) return CompletableFuture.completedFuture(
                BotExecutionResult.failed(request.executionId(),RUNNER_UNAVAILABLE,"QUICKJS_UNAVAILABLE_OR_UNVERIFIED"));
            if(!jobs.isEmpty()) return CompletableFuture.completedFuture(BotExecutionResult.failed(request.executionId(),RUNNER_UNAVAILABLE,"LOCAL_SLOT_BUSY"));
            jobs.put(request.executionId(),job);
            tasks.submit(()->run(request,job));
        }
        return job.result.minimalCompletionStage();
    }
    private void run(BotExecutionRequest request,Job job) {
        Path directory=null,script=null,fixture=null; Process process=null; BotExecutionResult result=null;
        long started=0;
        try {
            directory=Files.createTempDirectory("battlecode-trusted-");script=directory.resolve("control.mjs");fixture=directory.resolve("fixture.mjs");
            // Separate module scope: the reviewed bot receives only observation, no supervisor std/os bindings.
            Files.writeString(fixture,request.submission().sourceText()+"\nexport function invokeTrustedFixture(observation) {\n"
                +"if (typeof chooseMove !== 'function') throw new Error('missing chooseMove');\nreturn chooseMove(observation);\n}\n",StandardCharsets.UTF_8);
            Files.writeString(script,resource("/quickjs-bootstrap-prefix.js")+resource("/quickjs-bootstrap-suffix.js"),StandardCharsets.UTF_8);
            if(job.cancelled.get()) throw new CancellationException();
            ProcessBuilder builder=new ProcessBuilder(executable.toString(),"--memory-limit",Long.toString(request.limits().heapBytes()/1024),
                "--stack-size",Long.toString(request.limits().stackBytes()/1024),"-m","control.mjs");
            builder.directory(directory.toFile());
            // No shell, source on argv or inherited backend credentials. OS and CLI capabilities remain unproven.
            builder.environment().clear();
            String systemRoot=System.getenv("SystemRoot"); if(systemRoot!=null)builder.environment().put("SystemRoot",systemRoot);
            process=builder.start(); job.process=process; liveProcesses.incrementAndGet();
            if(job.cancelled.get()) throw new CancellationException();
            final Process child=process;
            Future<?> stderr=tasks.submit(()->drain(child.getErrorStream()));
            byte[] input=StrictActionCodec.observation(request);
            if(input.length>16384) throw new IOException("Observation cap");
            DataOutputStream out=new DataOutputStream(process.getOutputStream());out.writeInt(input.length);out.write(input);out.flush();
            DataInputStream in=new DataInputStream(process.getInputStream());
            Future<JsonNode> ready=tasks.submit(()->frame(in));
            JsonNode startup=ready.get(2,TimeUnit.SECONDS);
            if(!request.executionId().toString().equals(startup.path("executionId").asText()) || !startup.path("stage").asText().equals("STARTED"))
                throw new IOException("Invalid startup envelope");
            started=System.nanoTime();
            var cpuBefore=process.info().totalCpuDuration();
            byte[] go=StrictActionCodec.JSON.writeValueAsBytes(Map.of("executionId",request.executionId().toString(),"stage","GO"));
            out.writeInt(go.length);out.write(go);out.flush();out.close();
            Future<JsonNode> reply=tasks.submit(()->frame(in));
            JsonNode envelope;
            try { envelope=reply.get(request.limits().wallTimeMs(),TimeUnit.MILLISECONDS); }
            catch(TimeoutException e) {
                var cpuAfter=process.info().totalCpuDuration();
                boolean cpuEvidence=cpuBefore.isPresent() && cpuAfter.isPresent()
                    && cpuAfter.orElseThrow().minus(cpuBefore.orElseThrow()).toMillis()>=request.limits().cpuTimeMs();
                // Wall expiry alone cannot distinguish host starvation from bot work. Require per-process CPU evidence.
                throw new Outcome(new BotExecutionResult(request.executionId(),null,new BotExecutionFailure(
                    cpuEvidence?BOT_TIMEOUT:INTERNAL_ERROR,cpuEvidence?"GUEST_WALL_EXPIRED_WITH_CPU_EVIDENCE":"WALL_EXPIRED_WITHOUT_CPU_EVIDENCE"),elapsed(started)));
            }
            if(!process.waitFor(500,TimeUnit.MILLISECONDS)) throw new IOException("Guest did not exit");
            if(process.exitValue()!=0) throw new IOException("Unclassified guest crash");
            if(in.read()!=-1) throw new IOException("Extra control frame");
            stderr.get(500,TimeUnit.MILLISECONDS);
            if(!request.executionId().toString().equals(envelope.path("executionId").asText())) throw new IOException("Correlation mismatch");
            if(envelope.has("action") && envelope.size()==2 && !envelope.has("failure")) {
                BotAction action;
                try { action=StrictActionCodec.parse(StrictActionCodec.JSON.writeValueAsBytes(envelope.get("action")),request.limits().outputBytes()); }
                catch(IOException e) { throw new Outcome(new BotExecutionResult(request.executionId(),null,new BotExecutionFailure(INVALID_ACTION,"STRICT_ACTION_SCHEMA"),elapsed(started))); }
                result=new BotExecutionResult(request.executionId(),action,null,elapsed(started));
            } else if(envelope.size()==2 && envelope.has("failure") && !envelope.has("action")
                && Set.of("INVALID_ACTION","BOT_RUNTIME_ERROR").contains(envelope.path("failure").asText())) {
                result=new BotExecutionResult(request.executionId(),null,new BotExecutionFailure(
                    BotExecutionFailure.Code.valueOf(envelope.path("failure").asText()),"REVIEWED_FIXTURE_GUEST_FAILURE"),elapsed(started));
            } else throw new IOException("Invalid result envelope");
        } catch(Outcome e) { result=e.result; }
        catch(CancellationException e) { result=BotExecutionResult.failed(request.executionId(),MATCH_CANCELLED,"CANCELLED"); }
        catch(TimeoutException e) { result=BotExecutionResult.failed(request.executionId(),RUNNER_UNAVAILABLE,"BOOTSTRAP_DEADLINE"); }
        catch(InterruptedException e) { Thread.currentThread().interrupt();result=BotExecutionResult.failed(request.executionId(),MATCH_CANCELLED,"INTERRUPTED"); }
        catch(Exception e) { result=BotExecutionResult.failed(request.executionId(),process==null?RUNNER_UNAVAILABLE:RUNNER_CRASH,"PROCESS_OR_CONTROL_FAILURE"); }
        finally {
            if(process!=null) {
                if(kill(process))liveProcesses.decrementAndGet();
                else {quarantined=true;result=BotExecutionResult.failed(request.executionId(),RUNNER_CRASH,"CLEANUP_UNCONFIRMED_QUARANTINED");}
                try { process.getInputStream().close();process.getErrorStream().close();process.getOutputStream().close(); }catch(IOException ignored) { }
            }
            try {
                if(script!=null)Files.deleteIfExists(script);
                if(fixture!=null)Files.deleteIfExists(fixture);
                if(directory!=null)Files.deleteIfExists(directory);
            } catch(IOException e) {quarantined=true;result=BotExecutionResult.failed(request.executionId(),RUNNER_CRASH,"ARTIFACT_CLEANUP_FAILED_QUARANTINED");}
        }
        returnResult(request,job,result);
    }
    private void returnResult(BotExecutionRequest request,Job job,BotExecutionResult result) {
        // Completion follows stream/process/temp-file cleanup. Single slot is not released early.
        synchronized(this) { jobs.remove(request.executionId()); }
        job.result.complete(job.cancelled.get()?BotExecutionResult.failed(request.executionId(),MATCH_CANCELLED,"CANCELLED"):result);
    }
    private static long elapsed(long start) { return start==0?0:TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start); }
    static JsonNode frame(DataInputStream in) throws IOException {
        int length=in.readInt();if(length<1 || length>4096)throw new IOException("Malformed frame length");
        byte[] bytes=in.readNBytes(length);if(bytes.length!=length)throw new EOFException();
        return StrictActionCodec.JSON.readTree(bytes);
    }
    private static void drain(InputStream stream) {
        try { byte[] buffer=new byte[256];int total=0,n;while((n=stream.read(buffer))!=-1) {total+=n;if(total>2048)throw new IOException("Diagnostics cap");} }
        catch(IOException e) { /* No untrusted diagnostics reach backend logs. Process outcome remains authoritative. */ }
    }
    private static String resource(String path) throws IOException {
        try(var in=TrustedQuickJsRunner.class.getResourceAsStream(path)) {return new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8);}
    }
    private static boolean kill(Process process) {
        List<ProcessHandle> children=process.descendants().toList();children.forEach(ProcessHandle::destroyForcibly);
        if(process.isAlive()) process.destroyForcibly();
        boolean interrupted=false;
        try { process.waitFor(500,TimeUnit.MILLISECONDS); }
        catch(InterruptedException e) {interrupted=true;}
        if(interrupted)Thread.currentThread().interrupt();
        return !process.isAlive() && children.stream().noneMatch(ProcessHandle::isAlive);
    }
    public CompletionStage<Void> cancel(UUID id) {
        Job job;
        synchronized(this) {job=jobs.get(id);if(job==null)return CompletableFuture.completedFuture(null);job.cancelled.set(true);}
        Process process=job.process;if(process!=null && !kill(process))quarantined=true;
        return job.result.handle((r,e)->(Void)null);
    }
    public int liveProcesses() {return liveProcesses.get();}
    public void close() {
        List<UUID> ids;synchronized(this){closed=true;ids=List.copyOf(jobs.keySet());}
        ids.forEach(id->cancel(id).toCompletableFuture().join());tasks.shutdownNow();
        try {if(!tasks.awaitTermination(3,TimeUnit.SECONDS))throw new IllegalStateException("Runner threads did not stop");}
        catch(InterruptedException e){Thread.currentThread().interrupt();}
        if(liveProcesses.get()!=0)throw new IllegalStateException("Runner quarantined with unconfirmed process cleanup");
    }
}
