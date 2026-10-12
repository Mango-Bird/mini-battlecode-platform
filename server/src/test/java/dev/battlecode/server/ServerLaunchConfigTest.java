package dev.battlecode.server;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ServerLaunchConfigTest {
    @Test void webOnlyUsesRenderPortAndMandatoryExplicitOrigins() {
        var config=ServerLaunchConfig.parse(new String[0],Map.of("BATTLECODE_MODE","WEB_ONLY","PORT","10000","BATTLECODE_ORIGINS","https://arena.example"));
        assertEquals(ServerMode.WEB_ONLY,config.mode());assertEquals("0.0.0.0",config.web().bindAddress());assertEquals(10000,config.web().port());
        assertEquals(16,config.network().maxConnections());assertEquals(32,config.web().outboundCapacity());
    }
    @Test void rejectsMissingPortOriginsAndInvalidDeploymentSettings() {
        for(var env:List.of(Map.of("BATTLECODE_MODE","WEB_ONLY"),Map.of("BATTLECODE_MODE","WEB_ONLY","PORT","10000"),
            Map.of("BATTLECODE_MODE","WEB_ONLY","PORT","0","BATTLECODE_ORIGINS","https://arena.example"),
            Map.of("BATTLECODE_MODE","WEB_ONLY","PORT","10000","BATTLECODE_ORIGINS","*"),Map.of("BATTLECODE_MODE","typo")))
            assertThrows(IllegalArgumentException.class,()->ServerLaunchConfig.parse(new String[0],env));
    }
    @Test void localTcpDefaultsAndCombinedModeRemainCompatible() {
        var tcp=ServerLaunchConfig.parse(new String[0],Map.of());assertEquals(ServerMode.LOCAL,tcp.mode());assertNull(tcp.web());assertEquals(7777,tcp.network().port());
        var mixed=ServerLaunchConfig.parse(new String[]{"127.0.0.1","9000"},Map.of("BATTLECODE_WS_PORT","8081"));assertEquals(9000,mixed.network().port());assertEquals(8081,mixed.web().port());assertEquals(256,mixed.network().maxConnections());
    }
    @Test void renderPortWinsOverLegacyWebPortAndBind() {
        var config=ServerLaunchConfig.parse(new String[0],Map.of("BATTLECODE_MODE","WEB_ONLY","PORT","10000","BATTLECODE_WS_PORT","8081","BATTLECODE_WS_BIND","127.0.0.1","BATTLECODE_ORIGINS","https://arena.example"));
        assertEquals(10000,config.web().port());assertEquals("0.0.0.0",config.web().bindAddress());
    }
}
