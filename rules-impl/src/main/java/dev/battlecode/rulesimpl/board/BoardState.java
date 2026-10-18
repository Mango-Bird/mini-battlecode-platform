package dev.battlecode.rulesimpl.board;
import dev.battlecode.rulesapi.bot.*;
import java.util.*;
/** No identity/session mapping; all changes create a new immutable state. */
public final class BoardState {
    private final Board board;
    private final Side current;
    private final int validMoveCount;
    private final List<MoveEvent> history;
    private final Side winner;
    private final FinishReason reason;
    BoardState(Board board, Side current, int count, List<MoveEvent> history, Side winner, FinishReason reason) {
        this.board=Objects.requireNonNull(board); this.current=Objects.requireNonNull(current);
        if(count<0 || count>200 || winner!=null && reason==null) throw new IllegalArgumentException("Invalid board state");
        this.validMoveCount=count; this.history=List.copyOf(history); this.winner=winner; this.reason=reason;
    }
    public static BoardState initial() { return new BoardState(Board.initial(),Side.A,0,List.of(),null,null); }
    public Board board() { return board; }
    public Side current() { return current; }
    public int validMoveCount() { return validMoveCount; }
    public List<MoveEvent> history() { return history; }
    public Optional<Side> winner() { return Optional.ofNullable(winner); }
    public Optional<FinishReason> reason() { return Optional.ofNullable(reason); }
    public boolean finished() { return reason!=null; }
    public List<Move> legalMoves() { return finished()?List.of():board.legalMoves(current); }
    public BoardState forfeit(Side loser) {
        Objects.requireNonNull(loser);
        return finished()?this:new BoardState(board,current,validMoveCount,history,loser.opponent(),FinishReason.FORFEIT);
    }
    public BoardState play(Side player,Move move) {
        if(finished()) throw new IllegalStateException("Terminal board");
        if(player!=current) throw new IllegalArgumentException("Not requested turn");
        if(!board.legal(player,move)) return forfeit(player);
        Piece moving=board.piece(move.pieceId()).orElseThrow();
        Piece captured=board.at(move.to()).orElse(null);
        Board next=board.apply(player,move); int count=validMoveCount+1; Side nextSide=player.opponent();
        var events=new ArrayList<>(history);
        events.add(new MoveEvent(count,player,moving.id(),moving.position(),move.to(),captured));
        FinishReason finish=null; Side winning=null;
        if(move.to().equals(player.goal())) {finish=FinishReason.GOAL_REACHED; winning=player;}
        else if(Arrays.stream(PieceType.values()).anyMatch(type->next.pieces().stream()
                .noneMatch(piece->piece.owner()==nextSide && piece.type()==type))) {
            finish=FinishReason.PIECE_TYPE_ELIMINATED; winning=player;
        } else {
            finish=nextTurnReason(next,nextSide,count);
            if(finish==FinishReason.NO_LEGAL_MOVES) winning=player;
        }
        return new BoardState(next,nextSide,count,events,winning,finish);
    }
    static FinishReason nextTurnReason(Board board,Side nextSide,int count) {
        if(board.legalMoves(nextSide).isEmpty()) return FinishReason.NO_LEGAL_MOVES;
        return count==200?FinishReason.MOVE_LIMIT_REACHED:null;
    }
    /** Shape/type failure is adjudicated before movement and never appends a validated event. */
    public BoardState submit(Side player,Object logicalAction) {
        if(finished()) throw new IllegalStateException("Terminal board");
        if(player!=current) throw new IllegalArgumentException("Not requested turn");
        try {
            BotAction action=logicalAction instanceof BotAction typed?typed:BotAction.decode(logicalAction);
            return play(player,new Move(action.pieceId(),new Position(action.to().row(),action.to().col())));
        } catch(IllegalArgumentException ex) { return forfeit(player); }
    }
    public BotObservation observation(Side viewer,long remainingTimeMs) {
        Objects.requireNonNull(viewer);
        var pieces=board.pieces().stream().map(p->new BotPiece(p.id(),p.owner().name(),p.type().name(),p.position().row(),p.position().col())).toList();
        var moves=viewer==current?legalMoves().stream().map(m->new BotMove(m.pieceId(),new BotPosition(m.to().row(),m.to().col()))).toList():List.<BotMove>of();
        return new BotObservation(BotObservation.API_VERSION,BotObservation.GAME_ID,viewer.name(),validMoveCount+1,9,pieces,moves,remainingTimeMs);
    }
}
