package dev.battlecode.rulesapi.bot;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class BotContractTest {
    Map<String,Object> action(Object row,Object col) {return Map.of("pieceId","A-R1","to",Map.of("row",row,"col",col));}
    @Test void exactShapeAndIntegerCoordinates() {
        assertEquals(new BotAction("A-R1",new BotPosition(1,0)),BotAction.decode(action(1,0)));
        assertEquals(1,BotAction.decode(action(1L,0L)).to().row());
    }
    @Test void rejectsExtraMissingWrongTypesAndNulls() {
        var nullId=new HashMap<String,Object>();nullId.put("pieceId",null);nullId.put("to",Map.of("row",1,"col",0));
        var extra=new HashMap<>(action(1,0));extra.put("extra",true);
        for(Object value:Arrays.asList(null,"x",List.of(),Map.of(),nullId,extra,
            Map.of("pieceId","A-R1","to",Map.of("row",1,"col",0,"extra",0)), action(1.0,0),action("1",0),action(true,0),
            action(Long.MAX_VALUE,0),Map.of("pieceId",1,"to",Map.of("row",1,"col",0))))
            assertThrows(IllegalArgumentException.class,()->BotAction.decode(value));
    }
    @Test void observationCopiesSortsAndCannotBeMutated() {
        var pieces=new ArrayList<>(List.of(new BotPiece("B-R1","B","ROCK",7,6),new BotPiece("A-R1","A","ROCK",0,0)));
        var moves=new ArrayList<>(List.of(new BotMove("A-R2",new BotPosition(2,1)),new BotMove("A-R1",new BotPosition(2,0)),
            new BotMove("A-R1",new BotPosition(1,1)),new BotMove("A-R1",new BotPosition(1,0))));
        var obs=new BotObservation("bot-1","ott-board-9x9-v1","A",1,9,pieces,moves,731);
        pieces.clear();moves.clear();
        assertEquals("A-R1",obs.board().getFirst().id());assertEquals(new BotPosition(1,0),obs.legalMoves().getFirst().to());
        assertEquals(731,obs.remainingTimeMs());assertThrows(UnsupportedOperationException.class,()->obs.board().clear());
        assertThrows(UnsupportedOperationException.class,()->obs.legalMoves().clear());
    }
    @Test void observationRejectsInvalidMetadataOrBudget() {
        assertThrows(IllegalArgumentException.class,()->new BotObservation("bot-2","ott-board-9x9-v1","A",1,9,List.of(),List.of(),100));
        assertThrows(IllegalArgumentException.class,()->new BotObservation("bot-1","ott-board-9x9-v1","A",0,9,List.of(),List.of(),100));
        assertThrows(IllegalArgumentException.class,()->new BotObservation("bot-1","ott-board-9x9-v1","A",1,9,List.of(),List.of(),-1));
    }
}
