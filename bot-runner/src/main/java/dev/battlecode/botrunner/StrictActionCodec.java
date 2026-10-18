package dev.battlecode.botrunner;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import dev.battlecode.rulesapi.bot.*;
import java.io.*;
import java.util.*;

/** Logical schema stays in BotAction; duplicate/trailing/depth/byte checks belong at the boundary. */
public final class StrictActionCodec {
    static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8).maxStringLength(32768).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private StrictActionCodec() { }
    public static BotAction parse(byte[] bytes, int cap) throws IOException {
        if (bytes.length==0 || bytes.length>cap) throw new IOException("Action size exceeded");
        try { return BotAction.decode(JSON.readValue(bytes,Object.class)); }
        catch (IllegalArgumentException e) { throw new IOException("Invalid bot-1 action",e); }
    }
    static byte[] observation(BotExecutionRequest request) throws IOException {
        return JSON.writeValueAsBytes(Map.of("executionId",request.executionId().toString(),"observation",request.observation()));
    }
}
