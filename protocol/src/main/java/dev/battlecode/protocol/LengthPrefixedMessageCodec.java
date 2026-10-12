package dev.battlecode.protocol;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
/** Stateless codec. Caller owns stream lifetime and serializes writes per connection. */
public final class LengthPrefixedMessageCodec implements MessageCodec {
    public static final int MAX_FRAME_BYTES = 65_536;
    public Optional<Message> read(InputStream input) throws IOException {
        int first = input.read();
        if (first == -1) return Optional.empty();
        var stream = new DataInputStream(input);
        int length = (first << 24) | (stream.readUnsignedByte() << 16) | (stream.readUnsignedByte() << 8) | stream.readUnsignedByte();
        if (length <= 0 || length > MAX_FRAME_BYTES) throw new ProtocolException(ErrorCode.MESSAGE_TOO_LARGE, "Invalid frame size");
        byte[] body = stream.readNBytes(length);
        if (body.length != length) throw new EOFException("Truncated frame");
        var data = new DataInputStream(new ByteArrayInputStream(body));
        try {
            String version = readString(data), request = readString(data), session = readString(data);
            String commandName = readString(data);
            Command command;
            try { command = Command.valueOf(commandName); }
            catch (IllegalArgumentException ex) { throw new ProtocolException(ErrorCode.UNKNOWN_COMMAND, "Unknown command"); }
            var error = ErrorCode.valueOf(readString(data));
            int count = data.readUnsignedShort();
            if (count > 128) throw new IllegalArgumentException("Too many fields");
            var payload = new LinkedHashMap<String,String>();
            for (int i = 0; i < count; i++) {
                String key = readString(data), value = readString(data);
                if (key.isEmpty() || payload.putIfAbsent(key, value) != null) throw new IllegalArgumentException("Invalid or duplicate key");
            }
            if (data.available() != 0) throw new IllegalArgumentException("Trailing bytes");
            return Optional.of(new Message(version, request, session, command, error, payload));
        } catch (IllegalArgumentException | EOFException | CharacterCodingException ex) {
            throw new ProtocolException(ErrorCode.MALFORMED_MESSAGE, "Malformed frame body");
        }
    }
    public void write(OutputStream output, Message message) throws IOException {
        var buffer = new ByteArrayOutputStream();
        var data = new DataOutputStream(buffer);
        writeString(data, message.version()); writeString(data, message.requestId()); writeString(data, message.sessionId());
        writeString(data, message.command().name()); writeString(data, message.errorCode().name());
        data.writeShort(message.payload().size());
        for (var entry : new TreeMap<>(message.payload()).entrySet()) {
            if (entry.getKey().isEmpty()) throw new ProtocolException(ErrorCode.MALFORMED_MESSAGE, "Empty payload key");
            writeString(data, entry.getKey()); writeString(data, entry.getValue());
            if (buffer.size() > MAX_FRAME_BYTES) throw new ProtocolException(ErrorCode.MESSAGE_TOO_LARGE, "Frame too large");
        }
        if (buffer.size() > MAX_FRAME_BYTES) throw new ProtocolException(ErrorCode.MESSAGE_TOO_LARGE, "Frame too large");
        var target = new DataOutputStream(output);
        target.writeInt(buffer.size()); buffer.writeTo(target); target.flush();
    }
    private static String readString(DataInputStream input) throws IOException {
        int length = input.readUnsignedShort();
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new EOFException("Truncated string");
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    private static void writeString(DataOutputStream output, String value) throws IOException {
        var encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));
        int length = encoded.remaining();
        if (length > 65_535) throw new ProtocolException(ErrorCode.MESSAGE_TOO_LARGE, "String too large");
        byte[] bytes = new byte[length];
        encoded.get(bytes);
        output.writeShort(length); output.write(bytes, 0, length);
    }
}
