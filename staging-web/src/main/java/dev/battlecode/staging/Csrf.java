package dev.battlecode.staging;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

/** Signed synchronizer token bound to an authenticated session or a pre-auth HttpOnly nonce. */
final class Csrf {
    private final byte[] key;
    private final Clock clock;
    Csrf(String secret,Clock clock){key=secret.getBytes(StandardCharsets.US_ASCII);this.clock=clock;}
    static String random(){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return HexFormat.of().formatHex(bytes);}
    String issue(String binding){String payload=clock.instant().plusSeconds(1800).getEpochSecond()+"."+random();return payload+"."+sign(binding+":"+payload);}
    boolean valid(String binding,String token){if(binding==null||token==null||token.length()>200)return false;try{String[] parts=token.split("\\.",-1);
        if(parts.length!=3||Long.parseLong(parts[0])<=clock.instant().getEpochSecond()||Long.parseLong(parts[0])>clock.instant().plusSeconds(1800).getEpochSecond())return false;
        return MessageDigest.isEqual(sign(binding+":"+parts[0]+"."+parts[1]).getBytes(StandardCharsets.US_ASCII),parts[2].getBytes(StandardCharsets.US_ASCII));
        }catch(RuntimeException invalid){return false;}}
    private String sign(String text){try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));}
        catch(GeneralSecurityException failure){throw new IllegalStateException("CSRF unavailable");}}
}
