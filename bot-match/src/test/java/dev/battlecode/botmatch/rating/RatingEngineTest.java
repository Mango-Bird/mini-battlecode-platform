package dev.battlecode.botmatch.rating;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class RatingEngineTest {
    @Test void equalWinLossDraw(){var e=new EloRatingEngine();var win=e.update(1500,0,1500,0,1,RatingPolicy.V1);assertEquals(1516,win.a());assertEquals(1484,win.b());assertEquals(1500,e.update(1500,0,1500,0,.5,RatingPolicy.V1).a());assertEquals(1484,e.update(1500,0,1500,0,0,RatingPolicy.V1).a());}
    @Test void thresholdsAndUnequalPreSnapshots(){assertEquals(32,RatingPolicy.V1.k(19));assertEquals(24,RatingPolicy.V1.k(20));assertEquals(24,RatingPolicy.V1.k(99));assertEquals(16,RatingPolicy.V1.k(100));var u=new EloRatingEngine().update(1500,0,1900,100,1,RatingPolicy.V1);assertEquals(1500+32.0*10/11,u.a(),1e-10);assertEquals(1900-16.0*10/11,u.b(),1e-10);}
    @Test void glickmanReference(){var e=new Glicko2RatingEngine();var next=e.update(new Glicko2RatingEngine.Rating(1500,200,.06),List.of(new Glicko2RatingEngine.Opponent(new Glicko2RatingEngine.Rating(1400,30,.06),1),new Glicko2RatingEngine.Opponent(new Glicko2RatingEngine.Rating(1550,100,.06),0),new Glicko2RatingEngine.Opponent(new Glicko2RatingEngine.Rating(1700,300,.06),0)),.5);assertEquals(1464.06,next.rating(),.01);assertEquals(151.52,next.ratingDeviation(),.01);assertEquals(.059996,next.volatility(),.000001);}
    @Test void glickoInactivityAndEqualDraw(){var e=new Glicko2RatingEngine();var p=new Glicko2RatingEngine.Rating(1500,200,.06);var inactive=e.update(p,List.of(),.5);assertEquals(1500,inactive.rating());assertTrue(inactive.ratingDeviation()>200);var draw=e.update(p,List.of(new Glicko2RatingEngine.Opponent(p,.5)),.5);assertEquals(1500,draw.rating());assertTrue(draw.ratingDeviation()<200);}
    @Test void rejectsMalformedMath(){assertThrows(IllegalArgumentException.class,()->new EloRatingEngine().update(Double.NaN,0,1500,0,1,RatingPolicy.V1));assertThrows(IllegalArgumentException.class,()->new Glicko2RatingEngine.Opponent(new Glicko2RatingEngine.Rating(1500,350,.06),.7));}
}
