package dev.battlecode.botrunner;

import java.nio.*;
import java.nio.charset.*;
import java.security.*;
import java.util.*;

/** Frozen UTF-8 source; never printed by toString. Not an authorization to execute it. */
public final class BotSubmission {
    private final UUID id;
    private final int version;
    private final byte[] source;
    private final String sha256;
    public BotSubmission(UUID id, int version, byte[] source, String sha256) {
        this.id = Objects.requireNonNull(id);
        if (version < 1 || source == null || source.length == 0 || source.length > 32768)
            throw new IllegalArgumentException("Invalid source/version");
        this.version = version;
        this.source = source.clone();
        try { StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(this.source)); }
        catch (CharacterCodingException e) { throw new IllegalArgumentException("Source must be UTF-8", e); }
        this.sha256 = hash(this.source);
        if (!this.sha256.equals(sha256)) throw new IllegalArgumentException("Source SHA-256 mismatch");
    }
    public static BotSubmission of(UUID id, int version, String source) {
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        return new BotSubmission(id, version, bytes, hash(bytes));
    }
    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    public UUID submissionId() { return id; }
    public int version() { return version; }
    public byte[] source() { return source.clone(); }
    public String sourceText() { return new String(source, StandardCharsets.UTF_8); }
    public String sourceSha256() { return sha256; }
    @Override public String toString() { return "BotSubmission[" + id + ", version=" + version + ", sha256=" + sha256 + "]"; }
}
