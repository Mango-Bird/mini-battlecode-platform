-- Operator runs after Flyway V1-V5 using migration owner on a NEW dedicated staging database.
-- First create battlecode_staging_app LOGIN with a unique random secret using provider console.
-- No credentials are embedded here. Runtime must NOT own schema/tables or inherit owner role.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO battlecode_staging_app;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO battlecode_staging_app;
GRANT INSERT ON users,user_sessions,bots,bot_versions,idempotency_records TO battlecode_staging_app;
GRANT UPDATE ON user_sessions,bots TO battlecode_staging_app;
GRANT DELETE ON user_sessions TO battlecode_staging_app;
-- Deliberately no rating/queue/tournament/result writes, bot_version updates/deletes, or DDL.
-- New migrations require explicit grant review; do not grant blanket future table privileges.
