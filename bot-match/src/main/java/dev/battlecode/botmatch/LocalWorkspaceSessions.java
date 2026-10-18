package dev.battlecode.botmatch;
import java.security.SecureRandom;
import java.util.*;
/** Local browser ownership, separate from runner authentication; no guest ever receives these tokens. */
final class LocalWorkspaceSessions {
    private final Map<String,UUID> sessions=new HashMap<>();
    synchronized String issue(String previous) {
        if(previous!=null&&sessions.containsKey(previous))return previous;
        if(sessions.size()>=32)throw new IllegalStateException("Workspace session capacity");
        byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);
        String token=HexFormat.of().formatHex(bytes);sessions.put(token,UUID.randomUUID());return token;
    }
    synchronized UUID identity(String token) {
        UUID identity=token==null?null:sessions.get(token);
        if(identity==null)throw new SecurityException("Workspace authentication required");return identity;
    }
}
