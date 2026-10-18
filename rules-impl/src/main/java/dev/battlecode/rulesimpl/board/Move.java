package dev.battlecode.rulesimpl.board;
import java.util.Objects;
public record Move(String pieceId, Position to) {
    public Move { Objects.requireNonNull(pieceId); Objects.requireNonNull(to); }
}
