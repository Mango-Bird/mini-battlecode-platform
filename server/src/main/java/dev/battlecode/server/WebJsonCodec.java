package dev.battlecode.server;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import dev.battlecode.competition.RoomDetails;
import dev.battlecode.protocol.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Explicit projections only. No generic serialization of application services or RuleState. */
final class WebJsonCodec {
    static final int LIMIT = 65_536;
    private final ObjectMapper mapper = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4).maxStringLength(LIMIT).maxNumberLength(20).build())
            .build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    Message request(String text, String session) throws IOException {
        if (text.getBytes(StandardCharsets.UTF_8).length > LIMIT) throw new IOException("JSON too large");
        JsonNode root = mapper.readTree(text);
        if (root == null || !root.isObject() || root.size() != 3 || !root.has("requestId")
                || !root.has("command") || !root.has("payload") || !root.get("requestId").isTextual()
                || !root.get("command").isTextual() || !root.get("payload").isObject()) throw new IOException("Invalid envelope");
        var payload = new HashMap<String, String>();
        var fields = root.get("payload").fields();
        while (fields.hasNext()) {
            var field = fields.next();
            if (field.getKey().isEmpty() || !field.getValue().isTextual()) throw new IOException("Payload must be string fields");
            payload.put(field.getKey(), field.getValue().textValue());
        }
        try {
            Command command = Command.valueOf(root.get("command").textValue());
            // Repeated HELLO uses the bound identity so dispatcher returns INVALID_STATE, rather than accepting a new session.
            Message message = new Message(Message.VERSION, root.get("requestId").textValue(), session, command, ErrorCode.NONE, payload);
            new LengthPrefixedMessageCodec().write(new ByteArrayOutputStream(), message);
            return message;
        } catch (IllegalArgumentException ex) { throw new IOException("Invalid command or payload", ex); }
    }

    String message(Message message) throws IOException {
        return encode(Map.of("type", message.requestId().isEmpty() ? "event" : "response", "requestId", message.requestId(),
                "sessionId", message.sessionId(), "command", message.command().name(), "errorCode", message.errorCode().name(), "payload", message.payload()));
    }
    String room(UUID session, UUID roomId, long revision, RoomDetails details) throws IOException {
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("type", "room"); envelope.put("command", "ROOM_STATE"); envelope.put("sessionId", session.toString());
        envelope.put("roomId", roomId == null ? null : roomId.toString()); envelope.put("revision", revision);
        if (details != null && details.contains(session)) {
            envelope.put("room", Map.of("roomId", details.roomId().toString(), "ruleId", details.ruleId(), "status", details.status().name(),
                    "hostId", details.hostId().toString(), "capacity", details.capacity(), "revision", details.revision(),
                    "players", details.players().stream().map(player -> Map.of("playerId", player.playerId().toString(), "nickname", player.nickname())).toList()));
        } else envelope.put("room", null);
        return encode(envelope);
    }
    private String encode(Object projection) throws IOException {
        String json = mapper.writeValueAsString(projection);
        if (json.getBytes(StandardCharsets.UTF_8).length > LIMIT) throw new IOException("Web message too large");
        return json;
    }
}
