package dev.battlecode.rulesimpl.board;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class BoardTest {
    Piece piece(String id,int row,int col) {
        Side side=Side.valueOf(id.substring(0,1));
        PieceType type=switch(id.charAt(2)){case 'R'->PieceType.ROCK;case 'P'->PieceType.PAPER;default->PieceType.SCISSORS;};
        return new Piece(id,side,type,new Position(row,col));
    }
    Board board(Piece...pieces){return new Board(List.of(pieces));}
    BoardState state(Board board,Side side,int count){return new BoardState(board,side,count,List.of(),null,null);}
    @Test void initialHasTwelvePiecesTwoOfEachTypePerSide() {
        var board=Board.initial();assertEquals(12,board.pieces().size());
        for(Side side:Side.values())for(PieceType type:PieceType.values())
            assertEquals(2,board.pieces().stream().filter(p->p.owner()==side&&p.type()==type).count());
    }
    @Test void initialIdsAndCoordinatesAreExact() {
        var expected=Map.ofEntries(Map.entry("A-R1",new Position(0,0)),Map.entry("A-P1",new Position(0,1)),Map.entry("A-S1",new Position(0,2)),
            Map.entry("A-R2",new Position(1,0)),Map.entry("A-P2",new Position(1,1)),Map.entry("A-S2",new Position(1,2)),
            Map.entry("B-R1",new Position(7,6)),Map.entry("B-P1",new Position(7,7)),Map.entry("B-S1",new Position(7,8)),
            Map.entry("B-R2",new Position(8,6)),Map.entry("B-P2",new Position(8,7)),Map.entry("B-S2",new Position(8,8)));
        var actual=new HashMap<String,Position>();Board.initial().pieces().forEach(p->actual.put(p.id(),p.position()));
        assertEquals(expected,actual);assertEquals(new Position(8,8),Side.A.goal());assertEquals(new Position(0,0),Side.B.goal());
    }
    @Test void aStartsAndSidesAlternate() {
        var state=BoardState.initial();assertEquals(Side.A,state.current());
        state=state.play(Side.A,new Move("A-S2",new Position(2,2)));assertEquals(Side.B,state.current());
        state=state.play(Side.B,new Move("B-R1",new Position(6,6)));assertEquals(Side.A,state.current());assertEquals(2,state.validMoveCount());
    }
    @Test void allowsAllEightDirections() {
        var board=board(piece("A-R1",4,4));
        assertEquals(8,board.legalMoves(Side.A).size());
        for(int dr=-1;dr<=1;dr++)for(int dc=-1;dc<=1;dc++)if(dr!=0||dc!=0)
            assertTrue(board.legal(Side.A,new Move("A-R1",new Position(4+dr,4+dc))));
    }
    @Test void rejectsOutsideBoard() {
        for(int[] p:List.of(new int[]{-1,0},new int[]{0,-1},new int[]{9,0},new int[]{0,9}))
            assertThrows(IllegalArgumentException.class,()->new Position(p[0],p[1]));
    }
    @Test void rejectsStandingStill() {assertFalse(board(piece("A-R1",4,4)).legal(Side.A,new Move("A-R1",new Position(4,4))));}
    @Test void rejectsJumping() {assertFalse(board(piece("A-R1",4,4)).legal(Side.A,new Move("A-R1",new Position(6,4))));}
    @Test void rejectsOwnOccupiedSquare() {
        assertFalse(board(piece("A-R1",4,4),piece("A-S1",4,5)).legal(Side.A,new Move("A-R1",new Position(4,5))));
    }
    @Test void rockCapturesScissors() {capture("A-R1","B-S1");}
    @Test void scissorsCapturesPaper() {capture("A-S1","B-P1");}
    @Test void paperCapturesRock() {capture("A-P1","B-R1");}
    void capture(String a,String b) {
        var before=board(piece(a,4,4),piece(b,4,5));var after=before.apply(Side.A,new Move(a,new Position(4,5)));
        assertTrue(after.piece(b).isEmpty());assertEquals(new Position(4,5),after.piece(a).orElseThrow().position());
        assertEquals(2,before.pieces().size());assertEquals(1,after.pieces().size());
    }
    @Test void rejectsEqualTypeCapture() {
        for(String type:List.of("R","P","S"))assertFalse(board(piece("A-"+type+"1",4,4),piece("B-"+type+"1",4,5))
            .legal(Side.A,new Move("A-"+type+"1",new Position(4,5))));
    }
    @Test void rejectsCaptureOfCounterType() {
        for(String pair:List.of("RP","PS","SR"))assertFalse(board(piece("A-"+pair.charAt(0)+"1",4,4),piece("B-"+pair.charAt(1)+"1",4,5))
            .legal(Side.A,new Move("A-"+pair.charAt(0)+"1",new Position(4,5))));
    }
    @Test void rejectsEnemyMissingAndCapturedPiece() {
        var board=board(piece("A-R1",4,4),piece("B-S1",4,5));
        assertFalse(board.legal(Side.A,new Move("B-S1",new Position(5,5))));
        assertFalse(board.legal(Side.A,new Move("A-R2",new Position(5,5))));
        assertFalse(board.apply(Side.A,new Move("A-R1",new Position(4,5))).legal(Side.B,new Move("B-S1",new Position(5,5))));
    }
    @Test void aGoalWinsBeforeEliminationAndLimit() {
        var after=state(board(piece("A-R1",7,7),piece("B-S1",8,8)),Side.A,199)
            .play(Side.A,new Move("A-R1",new Position(8,8)));
        assertEquals(Side.A,after.winner().orElseThrow());assertEquals(FinishReason.GOAL_REACHED,after.reason().orElseThrow());
        assertEquals(200,after.validMoveCount());
    }
    @Test void bGoalWins() {
        var after=state(board(piece("B-R1",1,1),piece("A-S1",0,0)),Side.B,0)
            .play(Side.B,new Move("B-R1",new Position(0,0)));
        assertEquals(Side.B,after.winner().orElseThrow());assertEquals(FinishReason.GOAL_REACHED,after.reason().orElseThrow());
    }
    @Test void eliminatesBothPiecesOfOneType() {
        var state=state(board(piece("A-R1",4,4),piece("A-P1",0,1),piece("A-S1",0,2),piece("B-S1",4,5),piece("B-S2",4,6),
            piece("B-R1",7,7),piece("B-P1",7,8)),Side.A,0);
        state=state.play(Side.A,new Move("A-R1",new Position(4,5)));assertFalse(state.finished());
        state=state.play(Side.B,new Move("B-R1",new Position(7,6)));
        state=state.play(Side.A,new Move("A-R1",new Position(4,6)));
        assertEquals(FinishReason.PIECE_TYPE_ELIMINATED,state.reason().orElseThrow());assertEquals(Side.A,state.winner().orElseThrow());
    }
    @Test void nextSideHasNoLegalMovesAndLosesBeforeMoveLimit() {
        // Isolate start-of-turn adjudication; full post-move evaluation checks elimination first.
        var board=board(piece("B-R1",0,0),piece("A-P1",0,1),piece("A-P2",1,0),piece("A-R1",1,1));
        assertTrue(board.legalMoves(Side.B).isEmpty());
        assertEquals(FinishReason.NO_LEGAL_MOVES,BoardState.nextTurnReason(board,Side.B,200));
    }
    @Test void drawExactlyAtTwoHundredValidMovesAndReplay() {
        BoardState state=BoardState.initial();
        for(int n=0;n<200;n++) {
            Side side=n%2==0?Side.A:Side.B;
            Move move=side==Side.A?new Move("A-S2",new Position(n%4==0?2:1,2))
                :new Move("B-R1",new Position(n%4==1?6:7,6));
            state=state.play(side,move);
            assertEquals(n+1,state.validMoveCount());assertEquals(n==199,state.finished());
        }
        assertEquals(FinishReason.MOVE_LIMIT_REACHED,state.reason().orElseThrow());assertTrue(state.winner().isEmpty());
        assertEquals(200,state.history().size());assertEquals(state.board(),Board.replay(state.history()));
        BoardState terminal=state;assertThrows(IllegalStateException.class,()->terminal.play(Side.A,new Move("A-S2",new Position(2,2))));
    }
    @Test void invalidMoveForfeitsWithoutIncrementOrEvent() {
        BoardState before=BoardState.initial();BoardState after=before.play(Side.A,new Move("A-R1",new Position(0,1)));
        assertEquals(FinishReason.FORFEIT,after.reason().orElseThrow());assertEquals(Side.B,after.winner().orElseThrow());
        assertEquals(0,after.validMoveCount());assertEquals(before.board(),after.board());assertTrue(after.history().isEmpty());
    }
    @Test void malformedLogicalActionsForfeitWithoutRetry() {
        for(Object value:Arrays.asList(null,Map.of(),Map.of("pieceId","A-R1","to",Map.of("row",1.5,"col",0)),
            Map.of("pieceId","A-R1","to",Map.of("row",-1,"col",0)),Map.of("pieceId","A-R1","to",Map.of("row",2,"col",0),"extra",true))) {
            var state=BoardState.initial().submit(Side.A,value);assertEquals(FinishReason.FORFEIT,state.reason().orElseThrow());
            assertEquals(0,state.validMoveCount());assertTrue(state.history().isEmpty());
        }
    }
    @Test void typedBotActionUsesSameAdjudicationWithoutMutableInput() {
        var action=new dev.battlecode.rulesapi.bot.BotAction("A-S2",new dev.battlecode.rulesapi.bot.BotPosition(2,2));
        assertEquals(1,BoardState.initial().submit(Side.A,action).validMoveCount());
        var outside=new dev.battlecode.rulesapi.bot.BotAction("A-S2",new dev.battlecode.rulesapi.bot.BotPosition(9,2));
        assertEquals(FinishReason.FORFEIT,BoardState.initial().submit(Side.A,outside).reason().orElseThrow());
    }
    @Test void eliminationTakesPrecedenceOverMoveLimit() {
        var before=state(board(piece("A-R1",4,4),piece("B-S1",4,5),piece("B-R1",7,6),piece("B-P1",7,7)),Side.A,199);
        var after=before.play(Side.A,new Move("A-R1",new Position(4,5)));
        assertEquals(200,after.validMoveCount());assertEquals(FinishReason.PIECE_TYPE_ELIMINATED,after.reason().orElseThrow());
    }
    @Test void deterministicAndImmutableStateObservationAndHistory() {
        var before=BoardState.initial();var move=new Move("A-S2",new Position(2,2));
        var a=before.play(Side.A,move);var b=before.play(Side.A,move);assertEquals(a.board(),b.board());assertEquals(a.history(),b.history());
        assertThrows(UnsupportedOperationException.class,()->before.board().pieces().clear());
        assertThrows(UnsupportedOperationException.class,()->a.history().clear());
        var obs=before.observation(Side.A,431);assertEquals("A",obs.playerId());assertEquals(1,obs.turn());assertEquals(431,obs.remainingTimeMs());
        assertTrue(obs.board().stream().noneMatch(p->p.id().contains("-token")));
        assertThrows(UnsupportedOperationException.class,()->obs.legalMoves().clear());
        assertEquals(obs.legalMoves().stream().sorted(Comparator.comparing(dev.battlecode.rulesapi.bot.BotMove::pieceId)
            .thenComparingInt(m->m.to().row()).thenComparingInt(m->m.to().col())).toList(),obs.legalMoves());
        assertEquals(obs.board().stream().sorted(Comparator.comparing(dev.battlecode.rulesapi.bot.BotPiece::id)).toList(),obs.board());
    }
    @Test void replayChecksCaptureAndRejectsTamperedEvents() {
        var events=new ArrayList<MoveEvent>();Board current=Board.initial();Side side=Side.A;
        // Deterministic policy finds a capture within a bounded walk; replay validates its captured identity.
        var random=new Random(17);
        for(int n=1;n<=200;n++) {
            var legal=current.legalMoves(side);
            Board turnBoard=current;
            var captures=legal.stream().filter(m->currentAt(turnBoard,m)).toList();
            Move move=captures.isEmpty()?legal.get(random.nextInt(legal.size())):captures.getFirst();
            Piece moving=current.piece(move.pieceId()).orElseThrow();
            var event=new MoveEvent(n,side,move.pieceId(),moving.position(),move.to(),current.at(move.to()).orElse(null));
            events.add(event);current=current.apply(side,move);side=side.opponent();
            if(event.captured()!=null) break;
        }
        assertTrue(events.stream().anyMatch(e->e.captured()!=null));assertEquals(current,Board.replay(events));
        MoveEvent first=events.getFirst();var bad=new ArrayList<>(events);
        bad.set(0,new MoveEvent(2,first.side(),first.pieceId(),first.from(),first.to(),first.captured()));
        assertThrows(IllegalArgumentException.class,()->Board.replay(bad));
    }
    boolean currentAt(Board board,Move move){return board.at(move.to()).isPresent();}
    @Test void rejectsDuplicateIdsPositionsAndInvalidPieceIdentity() {
        assertThrows(IllegalArgumentException.class,()->board(piece("A-R1",0,0),piece("A-R1",1,1)));
        assertThrows(IllegalArgumentException.class,()->board(piece("A-R1",0,0),piece("B-R1",0,0)));
        assertThrows(IllegalArgumentException.class,()->new Piece("A-R9",Side.A,PieceType.ROCK,new Position(0,0)));
    }
}
