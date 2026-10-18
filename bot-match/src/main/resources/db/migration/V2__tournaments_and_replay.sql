CREATE TABLE tournaments (
 id UUID PRIMARY KEY, owner_id UUID NOT NULL REFERENCES users(id), name VARCHAR(80) NOT NULL,
 format VARCHAR(32) NOT NULL CHECK(format IN ('ROUND_ROBIN','SINGLE_ELIMINATION')),
 status VARCHAR(32) NOT NULL CHECK(status IN ('CREATED','REGISTRATION_OPEN','REGISTRATION_CLOSED','RUNNING','FINISHED','CANCELLED')),
 seed BIGINT NOT NULL, max_participants INTEGER NOT NULL CHECK(max_participants BETWEEN 2 AND 16),
 max_draw_rematches INTEGER NOT NULL CHECK(max_draw_rematches BETWEEN 0 AND 2), registration_deadline TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 domain_snapshot JSONB NOT NULL CHECK(octet_length(domain_snapshot::text)<=1048576)
);
CREATE INDEX tournaments_owner ON tournaments(owner_id);
CREATE INDEX tournaments_status ON tournaments(status);
CREATE TABLE tournament_entries (
 id UUID PRIMARY KEY, tournament_id UUID NOT NULL REFERENCES tournaments(id), owner_id UUID NOT NULL REFERENCES users(id),
 bot_id UUID NOT NULL, bot_version INTEGER NOT NULL, source_sha256 CHAR(64) NOT NULL, seed_order INTEGER NOT NULL,
 UNIQUE(tournament_id,owner_id), UNIQUE(tournament_id,id),
 FOREIGN KEY(bot_id,bot_version,source_sha256) REFERENCES bot_versions(bot_id,version,source_sha256)
);
CREATE TABLE tournament_matches (
 id UUID PRIMARY KEY, tournament_id UUID NOT NULL REFERENCES tournaments(id), round_number INTEGER NOT NULL,
 slot INTEGER NOT NULL, attempt INTEGER NOT NULL, bot_a_entry_id UUID NOT NULL, bot_b_entry_id UUID,
 status VARCHAR(32) NOT NULL, winner_entry_id UUID, result_reason VARCHAR(256),
 started_at TIMESTAMPTZ, finished_at TIMESTAMPTZ, replay_snapshot JSONB,
 UNIQUE(tournament_id,round_number,slot,attempt),
 FOREIGN KEY(tournament_id,bot_a_entry_id) REFERENCES tournament_entries(tournament_id,id),
 FOREIGN KEY(tournament_id,bot_b_entry_id) REFERENCES tournament_entries(tournament_id,id),
 FOREIGN KEY(tournament_id,winner_entry_id) REFERENCES tournament_entries(tournament_id,id),
 CHECK(replay_snapshot IS NULL OR octet_length(replay_snapshot::text)<=4194304)
);
CREATE INDEX tournament_match_status ON tournament_matches(tournament_id,status);
CREATE TABLE match_events (
 match_id UUID NOT NULL REFERENCES tournament_matches(id), sequence_number INTEGER NOT NULL CHECK(sequence_number>=0),
 event_payload JSONB NOT NULL CHECK(octet_length(event_payload::text)<=16384), PRIMARY KEY(match_id,sequence_number)
);
