package dev.battlecode.rulesapi.bot;
import java.util.*;
/** Decodes an already parsed logical bot-1 value, without executing or parsing JavaScript. */
public record BotAction(String pieceId, BotPosition to) {
    public BotAction {
        if (pieceId == null || pieceId.isBlank() || to == null) throw new IllegalArgumentException("Invalid action");
    }
    public static BotAction decode(Object value) {
        if (!(value instanceof Map<?,?> map) || !map.keySet().equals(Set.of("pieceId","to"))
            || !(map.get("pieceId") instanceof String id) || !(map.get("to") instanceof Map<?,?> target)
            || !target.keySet().equals(Set.of("row","col"))) throw new IllegalArgumentException("Invalid bot-1 action shape");
        return new BotAction(id, new BotPosition(integer(target.get("row")),integer(target.get("col"))));
    }
    private static int integer(Object value) {
        if (value instanceof Integer n) return n;
        if (value instanceof Long n && n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE) return n.intValue();
        throw new IllegalArgumentException("Coordinate must be an integer");
    }
}
