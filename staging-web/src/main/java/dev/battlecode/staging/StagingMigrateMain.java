package dev.battlecode.staging;
import org.flywaydb.core.Flyway;
import java.util.*;
/** Explicit operator/CI task, never called by HTTP runtime. Uses a separate privileged identity. */
public final class StagingMigrateMain {
    private StagingMigrateMain(){}
    public static void main(String[] args){
        var env=new HashMap<>(System.getenv());
        env.put("BATTLECODE_DB_USER",Objects.requireNonNull(env.get("BATTLECODE_MIGRATION_DB_USER")));
        env.put("BATTLECODE_DB_PASSWORD",Objects.requireNonNull(env.get("BATTLECODE_MIGRATION_DB_PASSWORD")));
        var config=new StagingConfig(env);
        try(var pool=config.pool()){Flyway.configure().dataSource(pool).locations("classpath:db/migration").cleanDisabled(true).load().migrate();}
    }
}
