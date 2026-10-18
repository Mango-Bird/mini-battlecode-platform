package dev.battlecode.rulesimpl.board;
import java.util.Objects;
/** A validated move; captured is null for a move into an empty square. */
public record MoveEvent(int number, Side side, String pieceId, Position from, Position to, Piece captured) {
    public MoveEvent {
        if (number < 1) throw new IllegalArgumentException("Invalid move number");
        Objects.requireNonNull(side); Objects.requireNonNull(pieceId); Objects.requireNonNull(from); Objects.requireNonNull(to);
    }
}
