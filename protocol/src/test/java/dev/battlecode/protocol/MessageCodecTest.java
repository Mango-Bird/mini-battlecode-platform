package dev.battlecode.protocol;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MessageCodecTest {
    private final MessageCodec codec = new LengthPrefixedMessageCodec();
    private final Message hello = new Message("1.0", "r1", "", Command.HELLO, ErrorCode.NONE, Map.of("name", "Người chơi"));
    private byte[] encode(Message message) throws IOException {
        var out = new ByteArrayOutputStream(); codec.write(out, message); return out.toByteArray();
    }
    @Test void roundTripUnicode() throws Exception { assertEquals(hello, codec.read(new ByteArrayInputStream(encode(hello))).orElseThrow()); }
    @Test void fragmentedAndCoalescedFrames() throws Exception {
        var out = new ByteArrayOutputStream(); codec.write(out, hello); codec.write(out, hello);
        var input = new FilterInputStream(new ByteArrayInputStream(out.toByteArray())) {
            public int read(byte[] b, int off, int len) throws IOException { return super.read(b, off, Math.min(1, len)); }
        };
        assertEquals(hello, codec.read(input).orElseThrow()); assertEquals(hello, codec.read(input).orElseThrow()); assertTrue(codec.read(input).isEmpty());
    }
    @Test void cleanEof() throws Exception { assertTrue(codec.read(new ByteArrayInputStream(new byte[0])).isEmpty()); }
    @Test void partialHeaderAndBody() throws Exception {
        byte[] frame = encode(hello);
        assertThrows(EOFException.class, () -> codec.read(new ByteArrayInputStream(new byte[]{0, 0})));
        assertThrows(EOFException.class, () -> codec.read(new ByteArrayInputStream(Arrays.copyOf(frame, frame.length - 1))));
    }
    @Test void rejectsOversizedAndNegativeLengthBeforeAllocation() {
        for (byte[] header : List.of(new byte[]{0,1,0,1}, new byte[]{-1,-1,-1,-1}, new byte[4]))
            assertEquals(ErrorCode.MESSAGE_TOO_LARGE, assertThrows(ProtocolException.class, () -> codec.read(new ByteArrayInputStream(header))).code());
    }
    @Test void oversizedWriteDoesNotEmitPartialFrame() {
        var out = new ByteArrayOutputStream();
        var message = new Message("1.0", "r", "", Command.HELLO, ErrorCode.NONE, Map.of("name", "a".repeat(65535)));
        assertThrows(ProtocolException.class, () -> codec.write(out, message)); assertEquals(0, out.size());
    }
    private byte[] raw(String command, byte[] version, String... pairs) throws IOException {
        var body = new ByteArrayOutputStream(); var d = new DataOutputStream(body);
        d.writeShort(version.length); d.write(version);
        for (String s : List.of("r", "", command, "NONE")) { byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8); d.writeShort(b.length); d.write(b); }
        d.writeShort(pairs.length / 2);
        for (String s : pairs) { byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8); d.writeShort(b.length); d.write(b); }
        var frame = new ByteArrayOutputStream(); var f = new DataOutputStream(frame); f.writeInt(body.size()); body.writeTo(f); return frame.toByteArray();
    }
    @Test void rejectsInvalidUtf8() throws Exception {
        byte[] frame = raw("HELLO", new byte[]{(byte)0xc3, 0x28});
        assertEquals(ErrorCode.MALFORMED_MESSAGE, assertThrows(ProtocolException.class, () -> codec.read(new ByteArrayInputStream(frame))).code());
    }
    @Test void rejectsDuplicateKeys() throws Exception {
        byte[] frame = raw("HELLO", new byte[]{49}, "name", "a", "name", "b");
        assertThrows(ProtocolException.class, () -> codec.read(new ByteArrayInputStream(frame)));
    }
    @Test void unknownCommandHasSpecificCode() throws Exception {
        byte[] frame = raw("BOGUS", new byte[]{49});
        assertEquals(ErrorCode.UNKNOWN_COMMAND, assertThrows(ProtocolException.class, () -> codec.read(new ByteArrayInputStream(frame))).code());
    }
    @Test void rejectsTrailingBytes() throws Exception {
        byte[] original = encode(hello), frame = Arrays.copyOf(original, original.length + 1);
        java.nio.ByteBuffer.wrap(frame).putInt(original.length - 3);
        assertThrows(ProtocolException.class, () -> codec.read(new ByteArrayInputStream(frame)));
    }
    @Test void envelopeIsImmutableAndConsistent() {
        var payload = new HashMap<String,String>(); payload.put("name", "A");
        var msg = new Message("1.0", "r", "", Command.HELLO, ErrorCode.NONE, payload); payload.clear();
        assertEquals("A", msg.payload().get("name")); assertThrows(UnsupportedOperationException.class, () -> msg.payload().clear());
        assertThrows(IllegalArgumentException.class, () -> new Message("1.0", "r", "", Command.ERROR, ErrorCode.NONE, Map.of()));
    }
}
