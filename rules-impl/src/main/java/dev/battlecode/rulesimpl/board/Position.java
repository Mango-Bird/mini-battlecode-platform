package dev.battlecode.rulesimpl.board;
public record Position(int row, int col) {
    public Position {
        if (row < 0 || row >= 9 || col < 0 || col >= 9) throw new IllegalArgumentException("Outside board");
    }
    public int distance(Position other) { return Math.max(Math.abs(row-other.row),Math.abs(col-other.col)); }
}
