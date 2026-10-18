package dev.battlecode.rulesimpl.board;
public enum Side {
    A, B;
    public Side opponent() { return this == A ? B : A; }
    public Position goal() { return this == A ? new Position(8,8) : new Position(0,0); }
}
