package dev.battlecode.botmatch.ranked;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;
class MatchmakingPolicyTest {
 @Test void windowsAreBounded(){var p=MatchmakingPolicy.V1;assertEquals(100,p.window(Duration.ZERO));assertEquals(100,p.window(Duration.ofSeconds(29)));assertEquals(150,p.window(Duration.ofSeconds(30)));assertEquals(250,p.window(Duration.ofSeconds(60)));assertEquals(350,p.window(Duration.ofSeconds(120)));assertEquals(350,p.window(Duration.ofDays(1)));}
}
