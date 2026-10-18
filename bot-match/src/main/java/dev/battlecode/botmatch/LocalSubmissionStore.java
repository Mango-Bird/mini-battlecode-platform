package dev.battlecode.botmatch;

import dev.battlecode.botrunner.BotSubmission;
import java.nio.*;
import java.nio.charset.*;
import java.time.Instant;
import java.util.*;

/** Source storage only. Reuses frozen BotSubmission; never invokes a runner. */
public final class LocalSubmissionStore implements SubmissionRepository {
    public static final int MAX_BOTS=32, MAX_VERSIONS=16, MAX_REQUESTS=1024;
    public record Version(int version,String source,String sourceSha256,String createdAt,
                          String validationStatus,String executionEligibility) {
        @Override public String toString() {return "Version["+version+", "+sourceSha256+"]";}
    }
    public record Metadata(UUID botId,String name,String createdAt,String updatedAt,int latestVersion,
                           String sourceSha256,String validationStatus,String executionEligibility) {}
    public record Detail(Metadata metadata,List<Version> versions) {
        public Detail {versions=List.copyOf(versions);}
    }
    public record Validation(UUID botId,int version,String sourceSha256,String validationStatus,
                             String staticValidation,String runtimeVerification,String executionEligibility,String reason) {}
    private record Memo(String fingerprint,Object response) {}
    private final Map<UUID,Detail> bots=new LinkedHashMap<>();
    private final Map<UUID,Memo> requests=new HashMap<>();
    private final Map<UUID,UUID> owners=new HashMap<>();
    public synchronized UUID ownerOf(UUID id){return owners.get(id);}
    public synchronized Detail createOwned(UUID owner,UUID request,String name,String source){var d=create(request,name,source);if(owner!=null)owners.putIfAbsent(d.metadata().botId(),owner);return d;}
    private void authorize(UUID owner,UUID id){if(owner!=null&&!owner.equals(owners.get(id)))throw new SecurityException("Not owner");}
    public synchronized Detail saveOwned(UUID owner,UUID request,UUID id,int expected,String name,String source){authorize(owner,id);return save(request,id,expected,name,source);}
    public synchronized Map<String,Object> deleteOwned(UUID owner,UUID request,UUID id,int expected){authorize(owner,id);return delete(request,id,expected);}
    public synchronized Validation validateOwned(UUID owner,UUID request,UUID id,int expected){authorize(owner,id);return validate(request,id,expected);}
    public synchronized List<Metadata> list() {return bots.values().stream().map(Detail::metadata).toList();}
    public synchronized List<Detail> snapshots() {return List.copyOf(bots.values());}
    public synchronized Detail get(UUID id) {
        Detail detail=bots.get(id);if(detail==null)throw new NoSuchElementException("Submission not found");return detail;
    }
    /** Exact saved version, not latest; immutable bytes survive later saves/deletion. */
    public synchronized BotSubmission freezeVersion(UUID id,int version,String expectedHash) {
        Version saved=get(id).versions().stream().filter(v->v.version()==version).findFirst()
            .orElseThrow(()->new NoSuchElementException("Version not found"));
        if(!saved.sourceSha256().equals(expectedHash))throw new IllegalArgumentException("Source hash mismatch");
        return new BotSubmission(id,version,saved.source().getBytes(StandardCharsets.UTF_8),expectedHash);
    }
    public synchronized Detail create(UUID request,String name,String source) {
        String validName=name(name);BotSubmission frozen=freeze(UUID.randomUUID(),1,source);
        String key="CREATE:"+validName+":"+frozen.sourceSha256();
        Object old=repeated(request,key);if(old!=null)return (Detail)old;
        if(bots.size()>=MAX_BOTS)throw new IllegalStateException("Bot capacity");
        Instant now=Instant.now();var v=version(frozen,now);
        var detail=new Detail(metadata(frozen.submissionId(),validName,now.toString(),now.toString(),v),List.of(v));
        bots.put(detail.metadata().botId(),detail);remember(request,key,detail);return detail;
    }
    public synchronized Detail save(UUID request,UUID id,int expected,String name,String source) {
        String validName=name(name);BotSubmission frozen=freeze(id,Math.max(1,expected+1),source);
        String key="SAVE:"+id+":"+expected+":"+validName+":"+frozen.sourceSha256();
        Object old=repeated(request,key);if(old!=null)return (Detail)old;
        Detail previous=get(id);check(previous,expected);
        if(previous.versions().size()>=MAX_VERSIONS)throw new IllegalStateException("Version capacity");
        Instant now=Instant.now();var v=version(frozen,now);var versions=new ArrayList<>(previous.versions());versions.add(v);
        var detail=new Detail(metadata(id,validName,previous.metadata().createdAt(),now.toString(),v),versions);
        bots.put(id,detail);remember(request,key,detail);return detail;
    }
    public synchronized Map<String,Object> delete(UUID request,UUID id,int expected) {
        String key="DELETE:"+id+":"+expected;Object old=repeated(request,key);
        if(old!=null) { @SuppressWarnings("unchecked") var response=(Map<String,Object>)old;return response; }
        check(get(id),expected);bots.remove(id);var response=Map.<String,Object>of("deleted",id);
        remember(request,key,response);return response;
    }
    public synchronized Validation validate(UUID request,UUID id,int expected) {
        String key="VALIDATE:"+id+":"+expected;Object old=repeated(request,key);if(old!=null)return (Validation)old;
        Detail detail=get(id);check(detail,expected);Version v=detail.versions().getLast();
        var result=new Validation(id,v.version(),v.sourceSha256(),"SOURCE_ACCEPTED","NOT_PERFORMED","NOT_VERIFIED",
            "NOT_EXECUTABLE","Only UTF-8/source/metadata validated. Phase 8C.5 proven isolation is required before execution.");
        remember(request,key,result);return result;
    }
    private Object repeated(UUID request,String key) {
        Objects.requireNonNull(request);Memo memo=requests.get(request);
        if(memo!=null) {if(!memo.fingerprint().equals(key))throw new IllegalStateException("Request ID conflict");return memo.response();}
        if(requests.size()>=MAX_REQUESTS)throw new IllegalStateException("Request capacity");return null;
    }
    private void remember(UUID request,String key,Object response) {requests.put(request,new Memo(key,response));}
    private static void check(Detail detail,int expected) {
        if(detail.metadata().latestVersion()!=expected)throw new IllegalStateException("Stale expectedVersion");
    }
    private static String name(String name) {
        if(name==null||name.isBlank()||name.length()>80||!name.equals(name.trim())||name.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid name");
        try {StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).encode(CharBuffer.wrap(name));}
        catch(CharacterCodingException e){throw new IllegalArgumentException("Invalid name encoding");}
        return name;
    }
    private static BotSubmission freeze(UUID id,int version,String source) {
        if(source==null)throw new IllegalArgumentException("Missing source");
        try {
            ByteBuffer bytes=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(source));
            byte[] exact=new byte[bytes.remaining()];bytes.get(exact);
            return new BotSubmission(id,version,exact,BotSubmission.hash(exact));
        }catch(CharacterCodingException e){throw new IllegalArgumentException("Invalid UTF-8 source");}
    }
    private static Version version(BotSubmission frozen,Instant at) {
        return new Version(frozen.version(),frozen.sourceText(),frozen.sourceSha256(),at.toString(),"SOURCE_ACCEPTED","NOT_EXECUTABLE");
    }
    private static Metadata metadata(UUID id,String name,String created,String updated,Version v) {
        return new Metadata(id,name,created,updated,v.version(),v.sourceSha256(),v.validationStatus(),v.executionEligibility());
    }
}
