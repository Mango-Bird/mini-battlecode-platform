package dev.battlecode.rulesapi.bot;
import java.util.Set;
public record BotPiece(String id, String owner, String type, int row, int col) {
    public BotPiece {
        if (id == null || id.isBlank() || owner == null || type == null || !Set.of("A","B").contains(owner)
            || !Set.of("ROCK","PAPER","SCISSORS").contains(type) || row < 0 || row > 8 || col < 0 || col > 8)
            throw new IllegalArgumentException("Invalid observed piece");
    }
}
