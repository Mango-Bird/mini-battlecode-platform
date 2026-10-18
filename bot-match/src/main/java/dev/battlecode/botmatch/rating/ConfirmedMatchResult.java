package dev.battlecode.botmatch.rating;

import java.util.*;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Only constructed from authoritative completion, never deserialized from HTTP input. */
public record ConfirmedMatchResult(UUID matchId,String gameMode,String rulesVersion,Bot a,Bot b,
                                   String outcome,String reason,boolean ranked,Instant completedAt){
    public record Bot(UUID botId,UUID ownerId,int version,String sourceSha256){
        public Bot{Objects.requireNonNull(botId);Objects.requireNonNull(ownerId);if(version<1||sourceSha256==null||!sourceSha256.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Frozen bot");}
    }
    public ConfirmedMatchResult{
        Objects.requireNonNull(matchId);Objects.requireNonNull(a);Objects.requireNonNull(b);Objects.requireNonNull(completedAt);
        if(gameMode==null||!gameMode.matches("[a-z0-9-]{1,64}")||rulesVersion==null||!rulesVersion.matches("[a-z0-9.-]{1,64}")
            ||!Set.of("A","B","DRAW","CANCELLED").contains(outcome)||reason==null||reason.length()>256)throw new IllegalArgumentException("Confirmed result");
    }
    public boolean eligible(){return ranked&&!a.ownerId.equals(b.ownerId)&&!a.botId.equals(b.botId)&&!outcome.equals("CANCELLED")
        &&gameMode.equals("ott-board-9x9-v1")&&rulesVersion.equals("ott-board-9x9-v1");}
    public double scoreA(){return outcome.equals("A")?1:outcome.equals("DRAW")?.5:0;}
    public String hash(){
        try{String canonical=String.join("\n",matchId.toString(),gameMode,rulesVersion,a.botId.toString(),a.ownerId.toString(),""+a.version,a.sourceSha256,
            b.botId.toString(),b.ownerId.toString(),""+b.version,b.sourceSha256,outcome,reason,Boolean.toString(ranked),completedAt.toString());
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
