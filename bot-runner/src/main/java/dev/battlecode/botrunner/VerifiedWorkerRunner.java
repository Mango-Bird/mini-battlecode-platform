package dev.battlecode.botrunner;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static dev.battlecode.botrunner.BotExecutionFailure.Code.*;

/** Local authenticated supervisor adapter. A live failed/incomplete gate never admits user source. */
public final class VerifiedWorkerRunner implements BotRunner {
    public static final Set<String> MANDATORY=Set.of("filesystem","network","subprocess","nonroot","readonlyRoot",
        "pidNamespace","processMemory","cpuTime","wallTime","heap","stack","output","fileDescriptors",
        "processCount","freshContext","moduleDenial","cleanup","imageIdentity","cleanEnvironment");
    public record Config(Path python,Path worker,Path docker,String imageId) {
        public Config {Objects.requireNonNull(python);Objects.requireNonNull(worker);Objects.requireNonNull(docker);
            if(!imageId.matches("sha256:[a-f0-9]{64}"))throw new IllegalArgumentException("Pinned image required");}
    }
    public record Capabilities(boolean executable,Map<String,String> controls,Map<String,String> tests,String runtimeFingerprint) {
        public Capabilities {controls=Map.copyOf(controls);tests=Map.copyOf(tests);}
    }
    private final Config config;
    private final String owner=UUID.randomUUID().toString().replace("-","");
    private final String token=HexFormat.of().formatHex(random());
    private final ExecutorService readers=Executors.newFixedThreadPool(2,Thread.ofPlatform().daemon(true).name("isolated-worker-").factory());
    private final Map<UUID,CompletableFuture<BotExecutionResult>> pending=new ConcurrentHashMap<>();
    private final CompletableFuture<JsonNode> hello=new CompletableFuture<>();
    private final AtomicBoolean closed=new AtomicBoolean();
    private final AtomicBoolean workerLost=new AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicLong matchGeneration=new java.util.concurrent.atomic.AtomicLong();
    private volatile Capabilities capabilities=unavailable();
    private Process process;
    private DataOutputStream output;
    private final Object writeLock=new Object();
    public VerifiedWorkerRunner(Config config) {
        ExecutionEnvironment.requireLocal(System.getenv());
        this.config=config;
        try {
            ProcessBuilder builder=new ProcessBuilder(config.python().toAbsolutePath().toString(),"-I",
                config.worker().toAbsolutePath().toString(),"--docker",config.docker().toAbsolutePath().toString(),
                "--image",config.imageId(),"--owner",owner);
            clearEnvironment(builder);
            process=builder.start();output=new DataOutputStream(process.getOutputStream());
            readers.submit(this::read);
            readers.submit(()-> {try(var errors=process.getErrorStream()){errors.transferTo(OutputStream.nullOutputStream());}catch(IOException ignored){}});
            send(Map.of("op","HELLO"));
            JsonNode report=hello.get(60,TimeUnit.SECONDS);
            var controls=new HashMap<String,String>();report.path("controls").fields().forEachRemaining(e->controls.put(e.getKey(),e.getValue().asText()));
            var tests=new HashMap<String,String>();report.path("tests").fields().forEachRemaining(e->tests.put(e.getKey(),e.getValue().asText()));
            boolean verified=report.path("executable").asBoolean(false)&&controls.keySet().equals(MANDATORY)
                &&controls.values().stream().allMatch("ENFORCED_AND_VERIFIED"::equals)
                &&!tests.isEmpty()&&tests.values().stream().allMatch("PASS"::equals)
                &&report.path("runtimeFingerprint").asText().endsWith(config.imageId())
                &&report.path("workerEpoch").asText().equals(owner);
            capabilities=new Capabilities(verified,controls,tests,report.path("runtimeFingerprint").asText("UNAVAILABLE"));
        }catch(Exception e){capabilities=unavailable();terminate();}
    }
    private static byte[] random(){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return bytes;}
    private static Capabilities unavailable(){
        var controls=new HashMap<String,String>();MANDATORY.forEach(key->controls.put(key,"UNAVAILABLE"));
        return new Capabilities(false,controls,Map.of(),"UNAVAILABLE");
    }
    public Capabilities capabilities(){return closed.get()||workerLost.get()?unavailable():capabilities;}
    public String runtimeFingerprint(){return capabilities().runtimeFingerprint();}
    ProcessHandle supervisorProcess(){return process.toHandle();}
    private void send(Map<String,Object> fields) throws IOException {
        var envelope=new HashMap<>(fields);envelope.put("protocolVersion","runner-1");envelope.put("auth",token);
        byte[] bytes=StrictActionCodec.JSON.writeValueAsBytes(envelope);
        if(bytes.length>131072)throw new IOException("Request cap");
        synchronized(writeLock){output.writeInt(bytes.length);output.write(bytes);output.flush();}
    }
    private void read() {
        try(var input=new DataInputStream(process.getInputStream())) {
            while(!closed.get()) {
                int length=input.readInt();if(length<1||length>8192)throw new IOException("Response cap");
                byte[] bytes=input.readNBytes(length);if(bytes.length!=length)throw new EOFException();
                JsonNode message=StrictActionCodec.JSON.readTree(bytes);
                if(!message.path("protocolVersion").asText().equals("runner-1")
                    ||!MessageDigest.isEqual(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                        message.path("auth").asText().getBytes(java.nio.charset.StandardCharsets.US_ASCII)))throw new IOException("Authentication");
                if(message.path("op").asText().equals("CAPABILITIES")) {hello.complete(message);continue;}
                UUID id=UUID.fromString(message.path("executionId").asText());
                var future=pending.get(id);if(future==null)continue;
                BotExecutionResult result;
                if(message.has("action")&&!message.has("failure")) {
                    try {result=new BotExecutionResult(id,StrictActionCodec.parse(StrictActionCodec.JSON.writeValueAsBytes(message.get("action")),1024),null,message.path("elapsedTimeMs").asLong(0));}
                    // Authenticated guest action still must satisfy BotAction's logical constraints (e.g. blank ID).
                    catch(IOException e){result=BotExecutionResult.failed(id,INVALID_ACTION,"STRICT_ACTION_SCHEMA");}
                }else if(message.has("failure")&&!message.has("action")) {
                    var code=BotExecutionFailure.Code.valueOf(message.path("failure").asText());
                    String evidence=message.path("evidence").asText();
                    boolean proof=!code.botCaused()||switch(code){
                        case BOT_TIMEOUT -> evidence.equals("KERNEL_PROCESS_CPU_TIMER");
                        case BOT_RESOURCE_LIMIT -> Set.of("NATIVE_HEAP_CAP","CGROUP_OOM_KILL","ACTION_OUTPUT_CAP").contains(evidence);
                        case BOT_RUNTIME_ERROR -> Set.of("JS_EXCEPTION","MISSING_CHOOSE_MOVE").contains(evidence);
                        case INVALID_ACTION -> evidence.equals("STRICT_ACTION_SCHEMA");
                        default -> false;
                    };
                    result=BotExecutionResult.failed(id,proof?code:INTERNAL_ERROR,proof?evidence:"UNPROVEN_WORKER_FAILURE");
                }else result=BotExecutionResult.failed(id,INTERNAL_ERROR,"WORKER_RESPONSE_SCHEMA");
                if(result.failure()!=null&&(result.failure().code()==RUNNER_CRASH
                    ||result.failure().reason().equals("GATE_OR_CAPACITY")))capabilities=unavailable();
                pending.remove(id,future);future.complete(result);
            }
        }catch(Exception e) {
            workerLost.set(true);
            capabilities=unavailable();hello.completeExceptionally(new IOException("Worker unavailable"));
            terminate();
            pending.forEach((id,future)->future.complete(BotExecutionResult.failed(id,RUNNER_CRASH,"WORKER_LOST")));
            pending.clear();
        }
    }
    public CompletionStage<BotExecutionResult> execute(BotExecutionRequest request) {
        return execute(request,matchGeneration.get());
    }
    private CompletionStage<BotExecutionResult> execute(BotExecutionRequest request,long generation) {
        UUID id=request.executionId();
        if(!capabilities().executable()||!request.limits().equals(BotExecutionLimits.LOCAL))
            return CompletableFuture.completedFuture(BotExecutionResult.failed(id,RUNNER_UNAVAILABLE,"MANDATORY_CAPABILITY_GATE"));
        var future=new CompletableFuture<BotExecutionResult>();
        if(pending.putIfAbsent(id,future)!=null)return CompletableFuture.completedFuture(BotExecutionResult.failed(id,INTERNAL_ERROR,"DUPLICATE_ADAPTER_REQUEST"));
        try {
            var source=request.submission();
            send(Map.of("op","EXECUTE","generation",generation,"executionId",id.toString(),"submission",Map.of("submissionId",source.submissionId().toString(),
                "version",source.version(),"sourceSha256",source.sourceSha256(),"sourceBase64",Base64.getEncoder().encodeToString(source.source())),
                "observation",request.observation(),"limits",request.limits()));
        }catch(IOException e){pending.remove(id);future.complete(BotExecutionResult.failed(id,RUNNER_UNAVAILABLE,"WORKER_TRANSPORT"));}
        return future.minimalCompletionStage();
    }
    public CompletionStage<Void> cancel(UUID id) {
        var future=pending.get(id);if(future==null)return CompletableFuture.completedFuture(null);
        try {send(Map.of("op","CANCEL","executionId",id.toString()));}
        catch(IOException e){capabilities=unavailable();terminate();future.complete(BotExecutionResult.failed(id,MATCH_CANCELLED,"WORKER_CANCEL_TRANSPORT"));}
        return future.handle((result,error)->(Void)null);
    }
    /** Borrowed per-match view: service close must not destroy the shared, verified supervisor. */
    public BotRunner matchScope() {
        long generation=matchGeneration.incrementAndGet();
        return new BotRunner() {
            public CompletionStage<BotExecutionResult> execute(BotExecutionRequest request){return VerifiedWorkerRunner.this.execute(request,generation);}
            public CompletionStage<Void> cancel(UUID id){return VerifiedWorkerRunner.this.cancel(id);}
            public String runtimeFingerprint(){return VerifiedWorkerRunner.this.runtimeFingerprint();}
            public void close(){}
        };
    }
    private static void clearEnvironment(ProcessBuilder builder) {
        String root=System.getenv("SystemRoot");builder.environment().clear();
        if(root!=null)builder.environment().put("SystemRoot",root);
    }
    private void emergencyCleanup() {
        try {
            var args=new ArrayList<>(List.of(config.docker().toString(),"--host",System.getProperty("os.name").startsWith("Windows")
                ?"npipe:////./pipe/dockerDesktopLinuxEngine":"unix:///var/run/docker.sock"));
            // Reconcile repeatedly: a daemon create accepted just before supervisor death can finish after the first scan.
            for(int scan=0;scan<6;scan++) {
                var query=new ArrayList<>(args);query.addAll(List.of("ps","--all","--filter","label=battlecode.worker="+owner,"--format","{{.ID}}"));
                ProcessBuilder builder=new ProcessBuilder(query);clearEnvironment(builder);var listing=builder.start();
                if(!listing.waitFor(5,TimeUnit.SECONDS)){listing.destroyForcibly();capabilities=unavailable();return;}
                if(listing.exitValue()!=0){capabilities=unavailable();return;}
                String ids=new String(listing.getInputStream().readNBytes(4096),java.nio.charset.StandardCharsets.US_ASCII);
                for(String id:ids.split("\\s+"))if(id.matches("[a-f0-9]{12,64}")){
                    var remove=new ArrayList<>(args);remove.addAll(List.of("rm","--force",id));
                    builder=new ProcessBuilder(remove);clearEnvironment(builder);var cleanup=builder.start();
                    if(!cleanup.waitFor(5,TimeUnit.SECONDS)){cleanup.destroyForcibly();capabilities=unavailable();return;}
                }
                if(scan<5)Thread.sleep(500);
            }
        }catch(Exception ignored){capabilities=unavailable();}
    }
    private void terminate() {
        if(process!=null){process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();}
        emergencyCleanup();
    }
    public void close() {
        if(!closed.compareAndSet(false,true))return;
        try {if(output!=null)output.close();if(process!=null&&!process.waitFor(5,TimeUnit.SECONDS))terminate();}
        catch(Exception e){terminate();}
        readers.shutdownNow();
        try {readers.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
        emergencyCleanup();
        pending.forEach((id,future)->future.complete(BotExecutionResult.failed(id,MATCH_CANCELLED,"WORKER_CLOSED")));pending.clear();
    }
}
