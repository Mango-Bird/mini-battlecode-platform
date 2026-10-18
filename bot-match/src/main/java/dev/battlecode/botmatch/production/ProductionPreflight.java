package dev.battlecode.botmatch.production;

import java.net.URI;
import java.util.*;

/** Offline policy checks only. Successful validation does not authorize online execution. */
public final class ProductionPreflight {
    private ProductionPreflight() {}

    public static void requireLocalRuntime(Map<String, String> environment) {
        if (!"local".equals(environment.getOrDefault("BATTLECODE_ENV", "local"))
                || environment.containsKey("RENDER")
                || "WEB_ONLY".equalsIgnoreCase(environment.get("BATTLECODE_MODE"))) {
            throw new IllegalArgumentException("Competitive runtime is local-only; online runtime is not implemented");
        }
    }

    /** Returns non-sensitive, immutable diagnostics; never retains or reports input values. */
    public static List<String> validate(Map<String, String> environment) {
        var errors = new ArrayList<String>();
        if (!Set.of("staging", "production").contains(environment.getOrDefault("BATTLECODE_ENV", "")))
            errors.add("Explicit staging or production environment required");
        for (String key : List.of("BATTLECODE_PUBLIC_API_ORIGIN", "BATTLECODE_TRUSTED_ORIGIN", "BATTLECODE_WORKER_ORIGIN")) {
            if (!httpsOrigin(environment.get(key))) errors.add(key + " requires one exact non-loopback HTTPS origin");
        }
        if (!"__Host-battlecode_session".equals(environment.get("BATTLECODE_COOKIE_NAME"))
                || !"true".equals(environment.get("BATTLECODE_COOKIE_SECURE"))
                || !"true".equals(environment.get("BATTLECODE_COOKIE_HTTP_ONLY"))
                || !"Lax".equals(environment.get("BATTLECODE_COOKIE_SAME_SITE")))
            errors.add("Host-only Secure HttpOnly SameSite=Lax cookie policy required");
        if (!"session-token".equals(environment.get("BATTLECODE_CSRF_POLICY")))
            errors.add("Session-bound CSRF protection required");
        // Do not accept arbitrary JDBC query options that can override certificate verification.
        String jdbc = environment.getOrDefault("BATTLECODE_DB_URL", "");
        try {
            URI uri = URI.create(jdbc.startsWith("jdbc:") ? jdbc.substring(5) : "");
            if (!jdbc.startsWith("jdbc:postgresql://") || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getFragment() != null || uri.getPath() == null || uri.getPath().length() < 2
                    || !"sslmode=verify-full".equals(uri.getRawQuery())) throw new IllegalArgumentException();
        } catch (IllegalArgumentException failure) { errors.add("Database URL requires verify-full TLS without credentials or overriding options"); }
        String password = environment.getOrDefault("BATTLECODE_DB_PASSWORD", "");
        if (password.length() < 32 || password.chars().distinct().count() < 12
                || password.toLowerCase(Locale.ROOT).matches(".*(replace|example|change|password).*"))
            errors.add("Database secret must be provisioned by secret manager with at least 32 characters");
        if (environment.getOrDefault("BATTLECODE_DB_USER", "").isBlank()
                || "postgres".equalsIgnoreCase(environment.get("BATTLECODE_DB_USER")))
            errors.add("Dedicated runtime database role required");
        if (!Set.of("microvm", "gvisor").contains(environment.getOrDefault("BATTLECODE_WORKER_ISOLATION", "")))
            errors.add("Separate hardened worker isolation required");
        if (!"mtls".equals(environment.get("BATTLECODE_WORKER_AUTH"))) errors.add("Authenticated private worker transport required");
        if (!"1".equals(environment.get("BATTLECODE_ACTIVE_COORDINATORS"))) errors.add("Exactly one active coordinator required");
        return List.copyOf(errors);
    }

    private static boolean httpsOrigin(String value) {
        try {
            URI uri = URI.create(Objects.requireNonNull(value));
            String host = uri.getHost();
            return "https".equals(uri.getScheme()) && host != null && host.contains(".")
                    && !host.endsWith(".") && !host.endsWith(".localhost")
                    && !host.matches("[0-9.]+") && uri.getUserInfo() == null
                    && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && uri.getRawPath().isEmpty() && (uri.getPort() == -1 || uri.getPort() == 443);
        } catch (IllegalArgumentException | NullPointerException failure) { return false; }
    }

    /** Intentionally no implementation to turn a configuration assertion into a security gate. */
    public static void requireOnlineRuntime(Map<String, String> environment) {
        validate(environment);
        throw new IllegalStateException("Online competitive runtime, worker attestation and staging verification are not implemented");
    }
}
