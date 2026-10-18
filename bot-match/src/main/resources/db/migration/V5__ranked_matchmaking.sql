CREATE TABLE ranked_queue_tickets (
 ticket_id UUID PRIMARY KEY, owner_id UUID NOT NULL REFERENCES users(id), bot_id UUID NOT NULL REFERENCES bots(id),
 bot_version INTEGER NOT NULL, source_sha256 CHAR(64) NOT NULL,
 game_mode VARCHAR(64) NOT NULL, rules_version VARCHAR(64) NOT NULL, elo DOUBLE PRECISION NOT NULL,
 queued_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 status VARCHAR(16) NOT NULL CHECK(status IN ('WAITING','RESERVED','RUNNING','COMPLETED','CANCELLED','EXPIRED','INTERRUPTED')),
 FOREIGN KEY(bot_id,bot_version,source_sha256) REFERENCES bot_versions(bot_id,version,source_sha256)
);
CREATE UNIQUE INDEX ranked_active_owner ON ranked_queue_tickets(owner_id) WHERE status IN ('WAITING','RESERVED','RUNNING');
CREATE UNIQUE INDEX ranked_active_bot ON ranked_queue_tickets(bot_id) WHERE status IN ('WAITING','RESERVED','RUNNING');
CREATE INDEX ranked_waiting ON ranked_queue_tickets(status,queued_at,ticket_id);
CREATE TABLE ranked_pairings (
 pairing_id UUID PRIMARY KEY, ticket_a UUID NOT NULL UNIQUE REFERENCES ranked_queue_tickets(ticket_id),
 ticket_b UUID NOT NULL UNIQUE REFERENCES ranked_queue_tickets(ticket_id), policy_version VARCHAR(64) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), status VARCHAR(16) NOT NULL CHECK(status IN ('RESERVED','RUNNING','COMPLETED','INTERRUPTED')),
 CHECK(ticket_a<>ticket_b)
);
CREATE TABLE ranked_games (
 match_id UUID PRIMARY KEY, pairing_id UUID NOT NULL REFERENCES ranked_pairings(pairing_id), game_number INTEGER NOT NULL CHECK(game_number IN (1,2)),
 status VARCHAR(16) NOT NULL CHECK(status IN ('RESERVED','RUNNING','COMPLETED','INTERRUPTED')),
 battle_id UUID UNIQUE, snapshot JSONB, completed_at TIMESTAMPTZ, UNIQUE(pairing_id,game_number)
);
CREATE TABLE matchmaking_audit_events (
 sequence BIGSERIAL PRIMARY KEY, owner_id UUID REFERENCES users(id), pairing_id UUID REFERENCES ranked_pairings(pairing_id),
 kind VARCHAR(64) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX matchmaking_rate ON matchmaking_audit_events(owner_id,created_at);
CREATE FUNCTION ranked_ticket_transition() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF NEW.owner_id<>OLD.owner_id OR NEW.bot_id<>OLD.bot_id OR NEW.bot_version<>OLD.bot_version OR NEW.source_sha256<>OLD.source_sha256 OR NEW.elo<>OLD.elo OR NEW.game_mode<>OLD.game_mode OR NEW.rules_version<>OLD.rules_version OR NEW.queued_at<>OLD.queued_at THEN RAISE EXCEPTION 'Frozen ticket'; END IF;
 IF NEW.status<>OLD.status AND NOT (
   OLD.status='WAITING' AND NEW.status IN ('RESERVED','CANCELLED','EXPIRED') OR
   OLD.status='RESERVED' AND NEW.status IN ('RUNNING','INTERRUPTED') OR
   OLD.status='RUNNING' AND NEW.status IN ('COMPLETED','INTERRUPTED')) THEN RAISE EXCEPTION 'Ticket transition'; END IF;
 RETURN NEW; END $$;
CREATE TRIGGER ranked_ticket_guard BEFORE UPDATE ON ranked_queue_tickets FOR EACH ROW EXECUTE FUNCTION ranked_ticket_transition();
CREATE FUNCTION ranked_execution_transition() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF NEW.status<>OLD.status AND NOT (
   OLD.status='RESERVED' AND NEW.status IN ('RUNNING','INTERRUPTED') OR
   OLD.status='RUNNING' AND NEW.status IN ('COMPLETED','INTERRUPTED')) THEN RAISE EXCEPTION 'Execution transition'; END IF;
 IF OLD.status IN ('COMPLETED','INTERRUPTED') AND NEW IS DISTINCT FROM OLD THEN RAISE EXCEPTION 'Terminal execution immutable'; END IF;
 RETURN NEW; END $$;
CREATE TRIGGER ranked_pairing_guard BEFORE UPDATE ON ranked_pairings FOR EACH ROW EXECUTE FUNCTION ranked_execution_transition();
CREATE TRIGGER ranked_game_guard BEFORE UPDATE ON ranked_games FOR EACH ROW EXECUTE FUNCTION ranked_execution_transition();
