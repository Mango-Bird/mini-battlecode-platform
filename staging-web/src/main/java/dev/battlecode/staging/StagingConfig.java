package dev.battlecode.staging;

import java.net.URI;
import java.util.*;
import com.zaxxer.hikari.*;

/** Separate gate; does not bypass or alter ProductionPreflight. No execution dependency is constructed. */
public final class StagingConfig {
    public static final String MODE="SAFE_STAGING_WEB_ONLY";
    public final String origin,host;
    final String proxySecret,csrfSecret,url,user,password;
    public final int port;
    public StagingConfig(Map<String,String> env) {
        if(!"staging".equals(env.get("BATTLECODE_ENV"))||!MODE.equals(env.get("BATTLECODE_MODE"))
            ||!"disabled".equals(env.get("BATTLECODE_EXECUTION")))throw new IllegalArgumentException("Explicit safe staging configuration required");
        for(String key:env.keySet()) if(key.startsWith("BATTLECODE_WORKER")||key.equals("DOCKER_HOST")||key.equals("BATTLECODE_ACTIVE_COORDINATORS"))
            throw new IllegalArgumentException("Worker/coordinator configuration forbidden in safe staging");
        origin=origin(env.get("STAGING_FRONTEND_ORIGIN"));host=URI.create(origin(env.get("STAGING_BACKEND_ORIGIN"))).getRawAuthority();
        proxySecret=secret(env.get("STAGING_PROXY_SECRET"));csrfSecret=secret(env.get("STAGING_CSRF_SECRET"));
        if(proxySecret.equals(csrfSecret))throw new IllegalArgumentException("Independent staging secrets required");
        url=env.get("BATTLECODE_DB_URL");
        try{URI db=URI.create(url.substring(5));if(!url.startsWith("jdbc:postgresql://")||db.getHost()==null||db.getUserInfo()!=null
            ||db.getFragment()!=null||db.getPath().length()<2||!"sslmode=verify-full".equals(db.getRawQuery())
            ||db.getHost().contains("-pooler"))throw new IllegalArgumentException();}
        catch(Exception invalid){throw new IllegalArgumentException("Direct PostgreSQL verify-full TLS URL required");}
        user=env.get("BATTLECODE_DB_USER");if(user==null||!user.matches("[a-z][a-z0-9_]{2,40}")||Set.of("postgres","neondb_owner").contains(user))throw new IllegalArgumentException("Dedicated application database role required");
        password=secret(env.get("BATTLECODE_DB_PASSWORD"));
        try{port=Integer.parseInt(env.getOrDefault("PORT","10000"));if(port<1||port>65535)throw new NumberFormatException();}
        catch(NumberFormatException invalid){throw new IllegalArgumentException("Invalid HTTP port");}
    }
    private static String secret(String value){if(value==null||!value.matches("[A-Za-z0-9_-]{43,128}")||value.chars().distinct().count()<12
        ||value.toLowerCase(Locale.ROOT).matches(".*(replace|example|password|change).*"))throw new IllegalArgumentException("Provision independent random staging secrets (32 bytes or more)");return value;}
    private static String origin(String text){try{var u=URI.create(text);if(!"https".equals(u.getScheme())||u.getHost()==null||!u.getHost().contains(".")
        ||u.getHost().matches("[0-9.]+")||u.getHost().endsWith(".localhost")||u.getHost().endsWith(".")||u.getUserInfo()!=null
        ||!u.getRawPath().isEmpty()||u.getRawQuery()!=null||u.getRawFragment()!=null||u.getPort()!=-1)throw new IllegalArgumentException();return text;}
        catch(Exception invalid){throw new IllegalArgumentException("Exact HTTPS staging origin required");}}
    public HikariDataSource pool(){var c=new HikariConfig();c.setJdbcUrl(url);c.setUsername(user);c.setPassword(password);
        c.setMaximumPoolSize(4);c.setMinimumIdle(0);c.setConnectionTimeout(8000);c.setValidationTimeout(2000);c.setIdleTimeout(10000);
        c.setMaxLifetime(300000);c.setKeepaliveTime(0);c.setInitializationFailTimeout(-1);c.setPoolName("safe-staging");
        c.addDataSourceProperty("connectTimeout","5");c.addDataSourceProperty("socketTimeout","15");
        c.addDataSourceProperty("sslfactory","org.postgresql.ssl.DefaultJavaSSLFactory");
        return new HikariDataSource(c);}
    @Override public String toString(){return "StagingConfig[SAFE_STAGING_WEB_ONLY; secrets redacted]";}
}
