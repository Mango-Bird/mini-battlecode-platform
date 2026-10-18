package dev.battlecode.rulesimpl.board;
import java.util.*;
/** Immutable board. Custom construction is package-private for domain internals/test fixtures. */
public final class Board {
    private final List<Piece> pieces;
    Board(Collection<Piece> pieces) {
        this.pieces = pieces.stream().sorted(Comparator.comparing(Piece::id)).toList();
        if (this.pieces.size() > 12 || this.pieces.stream().map(Piece::id).distinct().count() != this.pieces.size()
            || this.pieces.stream().map(Piece::position).distinct().count() != this.pieces.size())
            throw new IllegalArgumentException("Duplicate pieces/positions or excessive roster");
    }
    public static Board initial() {
        var pieces = new ArrayList<Piece>();
        for (Side side : Side.values()) for (int n=1;n<=2;n++) for (PieceType type : PieceType.values()) {
            int offset=type.ordinal();
            pieces.add(new Piece(side+"-"+type.name().charAt(0)+n,side,type,
                new Position(side == Side.A ? n-1 : n+6, side == Side.A ? offset : offset+6)));
        }
        return new Board(pieces);
    }
    public List<Piece> pieces() { return pieces; }
    public Optional<Piece> piece(String id) { return pieces.stream().filter(p -> p.id().equals(id)).findFirst(); }
    public Optional<Piece> at(Position position) { return pieces.stream().filter(p -> p.position().equals(position)).findFirst(); }
    public List<Move> legalMoves(Side side) {
        var moves = new ArrayList<Move>();
        for (Piece piece : pieces) if (piece.owner() == side)
            for(int row=Math.max(0,piece.position().row()-1);row<=Math.min(8,piece.position().row()+1);row++)
                for(int col=Math.max(0,piece.position().col()-1);col<=Math.min(8,piece.position().col()+1);col++) {
                    var move=new Move(piece.id(),new Position(row,col));
                    if (legal(side,move)) moves.add(move);
                }
        return moves.stream().sorted(Comparator.comparing(Move::pieceId).thenComparingInt(m->m.to().row())
            .thenComparingInt(m->m.to().col())).toList();
    }
    public boolean legal(Side side, Move move) {
        if (move == null) return false;
        Piece piece=piece(move.pieceId()).orElse(null);
        if (piece == null || piece.owner()!=side || piece.position().distance(move.to())!=1) return false;
        Piece target=at(move.to()).orElse(null);
        return target == null || target.owner()!=side && piece.type().beats(target.type());
    }
    Board apply(Side side,Move move) {
        if (!legal(side,move)) throw new IllegalArgumentException("Illegal move");
        Piece moving=piece(move.pieceId()).orElseThrow();
        var next=new ArrayList<Piece>();
        for(Piece piece:pieces) if(!piece.id().equals(moving.id()) && !piece.position().equals(move.to())) next.add(piece);
        next.add(new Piece(moving.id(),moving.owner(),moving.type(),move.to()));
        return new Board(next);
    }
    public static Board replay(List<MoveEvent> events) {
        Board board=initial(); Side side=Side.A; int number=1;
        for(MoveEvent event:List.copyOf(events)) {
            Piece moving=board.piece(event.pieceId()).orElseThrow(()->new IllegalArgumentException("Missing replay piece"));
            if(event.number()!=number++ || event.side()!=side || !event.from().equals(moving.position())
                || !Objects.equals(event.captured(),board.at(event.to()).orElse(null)))
                throw new IllegalArgumentException("Inconsistent replay event");
            board=board.apply(side,new Move(event.pieceId(),event.to())); side=side.opponent();
        }
        return board;
    }
    @Override public boolean equals(Object other) { return other instanceof Board board && pieces.equals(board.pieces); }
    @Override public int hashCode() { return pieces.hashCode(); }
}
