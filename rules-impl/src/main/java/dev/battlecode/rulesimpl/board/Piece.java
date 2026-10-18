package dev.battlecode.rulesimpl.board;
import java.util.Objects;
public record Piece(String id, Side owner, PieceType type, Position position) {
    public Piece {
        Objects.requireNonNull(owner); Objects.requireNonNull(type); Objects.requireNonNull(position);
        String prefix = owner + "-" + type.name().charAt(0);
        if (id == null || !(id.equals(prefix+"1") || id.equals(prefix+"2"))) throw new IllegalArgumentException("Invalid piece ID");
    }
}
