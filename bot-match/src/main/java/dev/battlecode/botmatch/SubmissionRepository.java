package dev.battlecode.botmatch;
import java.util.*;
import dev.battlecode.botrunner.BotSubmission;
/** Private source repository. Owner authorization is explicit on mutations. */
public interface SubmissionRepository {
    List<LocalSubmissionStore.Detail> snapshots();
    LocalSubmissionStore.Detail get(UUID id);
    BotSubmission freezeVersion(UUID id,int version,String hash);
    UUID ownerOf(UUID id);
    LocalSubmissionStore.Detail createOwned(UUID owner,UUID request,String name,String source);
    LocalSubmissionStore.Detail saveOwned(UUID owner,UUID request,UUID id,int expected,String name,String source);
    Map<String,Object> deleteOwned(UUID owner,UUID request,UUID id,int expected);
    LocalSubmissionStore.Validation validateOwned(UUID owner,UUID request,UUID id,int expected);
}
