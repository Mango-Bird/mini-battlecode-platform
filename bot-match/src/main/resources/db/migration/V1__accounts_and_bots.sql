CREATE TABLE users (
 id UUID PRIMARY KEY, username VARCHAR(32) NOT NULL UNIQUE CHECK(username ~ '^[a-z0-9_]{3,32}$'),
 password_hash VARCHAR(100) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE user_sessions (
 id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES users(id), token_hash CHAR(64) NOT NULL UNIQUE,
 expires_at TIMESTAMPTZ NOT NULL, revoked_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX sessions_user ON user_sessions(user_id);
CREATE INDEX sessions_expiry ON user_sessions(expires_at);
CREATE TABLE bots (
 id UUID PRIMARY KEY, owner_id UUID NOT NULL REFERENCES users(id), name VARCHAR(80) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL, latest_version INTEGER NOT NULL CHECK(latest_version BETWEEN 1 AND 16), deleted_at TIMESTAMPTZ
);
CREATE INDEX bots_owner ON bots(owner_id) WHERE deleted_at IS NULL;
CREATE TABLE bot_versions (
 bot_id UUID NOT NULL REFERENCES bots(id), version INTEGER NOT NULL CHECK(version BETWEEN 1 AND 16),
 source BYTEA NOT NULL CHECK(octet_length(source) BETWEEN 1 AND 32768), source_sha256 CHAR(64) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL, validation_status VARCHAR(32) NOT NULL,
 PRIMARY KEY(bot_id,version), UNIQUE(bot_id,version,source_sha256)
);
ALTER TABLE bots ADD CONSTRAINT latest_version_exists FOREIGN KEY(id,latest_version) REFERENCES bot_versions(bot_id,version) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE idempotency_records (
 scope VARCHAR(120) NOT NULL, request_id UUID NOT NULL, payload_hash CHAR(64) NOT NULL,
 response_snapshot JSONB NOT NULL CHECK(octet_length(response_snapshot::text)<=4194304),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY(scope,request_id)
);
CREATE INDEX idempotency_created ON idempotency_records(created_at);
-- Immutable exact UTF-8 bytes; delete is a tombstone so frozen entries remain resolvable.
CREATE FUNCTION reject_version_change() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Immutable bot version'; END $$;
CREATE TRIGGER immutable_bot_version BEFORE UPDATE OR DELETE ON bot_versions FOR EACH ROW EXECUTE FUNCTION reject_version_change();
