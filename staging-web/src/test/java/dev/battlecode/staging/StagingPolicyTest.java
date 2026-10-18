package dev.battlecode.staging;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class StagingPolicyTest {
    static Map<String,String> environment(){return new HashMap<>(Map.ofEntries(
        Map.entry("BATTLECODE_ENV","staging"),Map.entry("BATTLECODE_MODE",StagingConfig.MODE),Map.entry("BATTLECODE_EXECUTION","disabled"),
        Map.entry("STAGING_FRONTEND_ORIGIN","https://stage.vercel.app"),Map.entry("STAGING_BACKEND_ORIGIN","https://stage.onrender.com"),
        Map.entry("STAGING_PROXY_SECRET","a0b1c2d3e4f5g6h7i8j9k0l1m2n3o4p5q6r7s8t9u0v1"),
        Map.entry("STAGING_CSRF_SECRET","z9y8x7w6v5u4t3s2r1q0p9o8n7m6l5k4j3i2h1g0f9e8"),
        Map.entry("BATTLECODE_DB_URL","jdbc:postgresql://neon.example.invalid/stage?sslmode=verify-full"),
        Map.entry("BATTLECODE_DB_USER","staging_app"),Map.entry("BATTLECODE_DB_PASSWORD","m9n8b7v6c5x4z3a2s1d0f9g8h7j6k5l4p3o2i1u0y9t8")));}
    @Test void explicitSeparateGateAndRedaction(){var env=environment();assertNotNull(new StagingConfig(env));
        assertFalse(new StagingConfig(env).toString().contains(env.get("BATTLECODE_DB_PASSWORD")));
        for(String key:env.keySet()){var missing=environment();missing.remove(key);assertThrows(IllegalArgumentException.class,()->new StagingConfig(missing),key);}}
    @Test void unsafeWorkerProductionTlsOriginsRejected(){for(var entry:Map.of("BATTLECODE_ENV","production","BATTLECODE_EXECUTION","enabled","BATTLECODE_MODE","WEB_ONLY",
        "BATTLECODE_DB_URL","jdbc:postgresql://neon.example.invalid/stage?sslmode=require","STAGING_FRONTEND_ORIGIN","*","STAGING_PROXY_SECRET","weak").entrySet()){
        var env=environment();env.put(entry.getKey(),entry.getValue());assertThrows(IllegalArgumentException.class,()->new StagingConfig(env));}
        var worker=environment();worker.put("BATTLECODE_WORKER_AUTH","mtls");assertThrows(IllegalArgumentException.class,()->new StagingConfig(worker));
        assertThrows(IllegalStateException.class,()->dev.battlecode.botrunner.ExecutionEnvironment.requireLocal(environment()));}
    @Test void csrfIsBoundExpiringAndTamperResistant(){var now=Instant.parse("2026-10-16T00:00:00Z");String secret=environment().get("STAGING_CSRF_SECRET");
        var csrf=new Csrf(secret,Clock.fixed(now,ZoneOffset.UTC));String token=csrf.issue("session:one");assertTrue(csrf.valid("session:one",token));
        assertFalse(csrf.valid("session:two",token));assertFalse(csrf.valid("pre:one",token));assertFalse(csrf.valid("session:one",token+"x"));
        assertFalse(new Csrf(secret,Clock.fixed(now.plusSeconds(1801),ZoneOffset.UTC)).valid("session:one",token));}
    @Test void routeRegistryHasNoExecutionOrFallback(){for(String route:List.of("battles","ranked/queue","tournaments","execution-capabilities","worker","execute","admin","../local/bots"))
        for(String method:List.of("GET","POST","DELETE","PUT"))assertFalse(StagingHttpServer.allowed(method,route),route);
        assertTrue(StagingHttpServer.allowed("GET","auth/csrf"));assertTrue(StagingHttpServer.allowed("POST","submissions"));assertFalse(StagingHttpServer.allowed("PATCH","submissions"));}
    @Test void boundedPoolConfiguration(){try(var pool=new StagingConfig(environment()).pool()){
        assertEquals(4,pool.getMaximumPoolSize());assertEquals(0,pool.getMinimumIdle());assertEquals(8000,pool.getConnectionTimeout());
        assertEquals("org.postgresql.ssl.DefaultJavaSSLFactory",pool.getDataSourceProperties().getProperty("sslfactory"));}}
}
