package dev.battlecode.rulesimpl.board;
public enum PieceType {
    ROCK, PAPER, SCISSORS;
    public boolean beats(PieceType other) {
        return this == ROCK && other == SCISSORS || this == SCISSORS && other == PAPER || this == PAPER && other == ROCK;
    }
}
