package dev.battlecode.botmatch.production;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProductionPreflightTest {
    private Map<String,String> policy() {
        return new HashMap<>(Map.ofEntries(
            Map.entry("BATTLECODE_ENV","staging"),
            Map.entry("BATTLECODE_PUBLIC_API_ORIGIN","https://api.example.invalid"),
            Map.entry("BATTLECODE_TRUSTED_ORIGIN","https://app.example.invalid"),
            Map.entry("BATTLECODE_WORKER_ORIGIN","https://worker.example.invalid"),
            Map.entry("BATTLECODE_COOKIE_NAME","__Host-battlecode_session"),
            Map.entry("BATTLECODE_COOKIE_SECURE","true"),
            Map.entry("BATTLECODE_COOKIE_HTTP_ONLY","true"),
            Map.entry("BATTLECODE_COOKIE_SAME_SITE","Lax"),
            Map.entry("BATTLECODE_CSRF_POLICY","session-token"),
            Map.entry("BATTLECODE_DB_URL","jdbc:postgresql://db.example.invalid/battlecode?sslmode=verify-full"),
            Map.entry("BATTLECODE_DB_USER","battlecode_runtime"),
            Map.entry("BATTLECODE_DB_PASSWORD","A9b8C7d6E5f4G3h2I1j0K9l8M7n6O5p4"),
            Map.entry("BATTLECODE_WORKER_ISOLATION","microvm"),
            Map.entry("BATTLECODE_WORKER_AUTH","mtls"),
            Map.entry("BATTLECODE_ACTIVE_COORDINATORS","1")));
    }
    @Test void existingLocalDefaultsRemainLocal() {
        assertDoesNotThrow(()->ProductionPreflight.requireLocalRuntime(Map.of()));
        assertDoesNotThrow(()->ProductionPreflight.requireLocalRuntime(Map.of("BATTLECODE_ENV","local")));
    }
    @Test void onlineEnvironmentsCannotStartLocalTransport() {
        for(String value:List.of("staging","production","","LOCAL","typo"))
            assertThrows(IllegalArgumentException.class,()->ProductionPreflight.requireLocalRuntime(Map.of("BATTLECODE_ENV",value)));
        assertThrows(IllegalArgumentException.class,()->ProductionPreflight.requireLocalRuntime(Map.of("RENDER","true")));
        assertThrows(IllegalArgumentException.class,()->ProductionPreflight.requireLocalRuntime(Map.of("BATTLECODE_MODE","WEB_ONLY")));
    }
    @Test void validDeclarationsNeverEnableOnlineExecution() {
        assertTrue(ProductionPreflight.validate(policy()).isEmpty());
        assertThrows(IllegalStateException.class,()->ProductionPreflight.requireOnlineRuntime(policy()));
        assertThrows(IllegalStateException.class,()->ProductionPreflight.requireOnlineRuntime(Map.of()));
    }
    @Test void everyRequiredSettingFailsClosedWhenMissing() {
        for(String key:policy().keySet()) {
            var missing=policy();missing.remove(key);
            assertFalse(ProductionPreflight.validate(missing).isEmpty(),key);
        }
    }
    @Test void originsCookiesAndCsrfRejectUnsafePolicies() {
        for(String origin:List.of("*","null","http://app.example.invalid","https://localhost","https://127.0.0.1",
                "https://x.localhost","https://app.example.invalid/","https://app.example.invalid:8443",
                "https://user:secret@app.example.invalid","https://app.example.invalid?x=1")) {
            var input=policy();input.put("BATTLECODE_TRUSTED_ORIGIN",origin);
            assertFalse(ProductionPreflight.validate(input).isEmpty(),origin);
        }
        for(String key:List.of("BATTLECODE_COOKIE_SECURE","BATTLECODE_COOKIE_HTTP_ONLY","BATTLECODE_CSRF_POLICY")) {
            var input=policy();input.put(key,"false");assertFalse(ProductionPreflight.validate(input).isEmpty());
        }
    }
    @Test void tlsOverridesWeakSecretsAndUnisolatedWorkersAreRejectedWithoutDisclosure() {
        for(String url:List.of("jdbc:postgresql://db/x?sslmode=require","jdbc:postgresql://db/x",
                "jdbc:postgresql://user:secret@db/x?sslmode=verify-full",
                "jdbc:postgresql://db/x?sslmode=verify-full&sslfactory=org.postgresql.ssl.NonValidatingFactory")) {
            var input=policy();input.put("BATTLECODE_DB_URL",url);assertFalse(ProductionPreflight.validate(input).isEmpty());
        }
        for(var entry:Map.of("BATTLECODE_DB_PASSWORD","secret-leak-marker","BATTLECODE_WORKER_ISOLATION","docker",
                "BATTLECODE_WORKER_AUTH","none","BATTLECODE_ACTIVE_COORDINATORS","2").entrySet()) {
            var input=policy();input.put(entry.getKey(),entry.getValue());
            var errors=ProductionPreflight.validate(input);assertFalse(errors.isEmpty());
            assertFalse(errors.toString().contains("secret-leak-marker"));
            assertThrows(UnsupportedOperationException.class,()->errors.add("change"));
        }
    }
}
