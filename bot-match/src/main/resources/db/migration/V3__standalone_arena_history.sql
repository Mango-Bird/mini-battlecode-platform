CREATE TABLE arena_battles (
 request_id UUID PRIMARY KEY, owner_id UUID NOT NULL REFERENCES users(id), payload_hash CHAR(64) NOT NULL,
 battle_id UUID UNIQUE, status VARCHAR(32) NOT NULL CHECK(status IN ('PENDING','RUNNING','FINISHED','CANCELLED','REJECTED')),
 snapshot JSONB CHECK(snapshot IS NULL OR octet_length(snapshot::text)<=4194304),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX arena_owner ON arena_battles(owner_id,created_at);
