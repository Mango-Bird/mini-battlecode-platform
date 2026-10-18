package dev.battlecode.rulesapi.bot;
import java.util.Objects;
public record BotMove(String pieceId, BotPosition to) {
    public BotMove { Objects.requireNonNull(pieceId); Objects.requireNonNull(to); }
}
