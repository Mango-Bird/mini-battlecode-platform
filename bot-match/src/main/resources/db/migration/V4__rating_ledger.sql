CREATE TABLE rating_policy_activation (version VARCHAR(64) PRIMARY KEY, activated_at TIMESTAMPTZ NOT NULL DEFAULT now());
INSERT INTO rating_policy_activation(version) VALUES ('rating-v1');
CREATE TABLE rating_periods (
 id BIGINT PRIMARY KEY, policy_version VARCHAR(64) NOT NULL, state VARCHAR(12) NOT NULL CHECK(state IN ('OPEN','CLOSED')),
 starts_at TIMESTAMPTZ NOT NULL, ends_at TIMESTAMPTZ NOT NULL, closed_at TIMESTAMPTZ
);
CREATE TABLE rating_profiles (
 bot_id UUID NOT NULL REFERENCES bots(id), game_mode VARCHAR(64) NOT NULL, rules_version VARCHAR(64) NOT NULL,
 policy_version VARCHAR(64) NOT NULL, elo DOUBLE PRECISION NOT NULL, rating DOUBLE PRECISION NOT NULL,
 rd DOUBLE PRECISION NOT NULL CHECK(rd>0), volatility DOUBLE PRECISION NOT NULL CHECK(volatility>0),
 rating_period BIGINT NOT NULL, games_in_period INTEGER NOT NULL DEFAULT 0, games INTEGER NOT NULL DEFAULT 0,
 wins INTEGER NOT NULL DEFAULT 0, draws INTEGER NOT NULL DEFAULT 0, losses INTEGER NOT NULL DEFAULT 0,
 PRIMARY KEY(bot_id,game_mode,rules_version)
);
CREATE TABLE match_scoring_ledger (
 sequence BIGSERIAL UNIQUE NOT NULL, match_id UUID PRIMARY KEY, result_hash CHAR(64) NOT NULL,
 canonical_result JSONB NOT NULL, eligible BOOLEAN NOT NULL, policy_version VARCHAR(64) NOT NULL,
 completed_at TIMESTAMPTZ NOT NULL, recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(), period_id BIGINT REFERENCES rating_periods(id)
);
CREATE TABLE rating_events (
 sequence BIGSERIAL PRIMARY KEY, match_id UUID NOT NULL REFERENCES match_scoring_ledger(match_id),
 bot_id UUID NOT NULL, game_mode VARCHAR(64) NOT NULL, rules_version VARCHAR(64) NOT NULL,
 period_id BIGINT NOT NULL REFERENCES rating_periods(id), opponent_bot_id UUID NOT NULL, score DOUBLE PRECISION NOT NULL CHECK(score IN (0,0.5,1)),
 elo_before DOUBLE PRECISION NOT NULL, elo_after DOUBLE PRECISION NOT NULL, k DOUBLE PRECISION NOT NULL,
 rating_before DOUBLE PRECISION NOT NULL, rd_before DOUBLE PRECISION NOT NULL, volatility_before DOUBLE PRECISION NOT NULL,
 opponent_rating DOUBLE PRECISION NOT NULL, opponent_rd DOUBLE PRECISION NOT NULL, opponent_volatility DOUBLE PRECISION NOT NULL,
 UNIQUE(match_id,bot_id), FOREIGN KEY(bot_id,game_mode,rules_version) REFERENCES rating_profiles(bot_id,game_mode,rules_version)
);
CREATE INDEX rating_events_period ON rating_events(period_id,bot_id);
CREATE TABLE rating_period_updates (
 period_id BIGINT NOT NULL REFERENCES rating_periods(id), bot_id UUID NOT NULL, game_mode VARCHAR(64) NOT NULL, rules_version VARCHAR(64) NOT NULL,
 rating_before DOUBLE PRECISION NOT NULL, rd_before DOUBLE PRECISION NOT NULL, volatility_before DOUBLE PRECISION NOT NULL,
 rating_after DOUBLE PRECISION NOT NULL, rd_after DOUBLE PRECISION NOT NULL, volatility_after DOUBLE PRECISION NOT NULL, games INTEGER NOT NULL,
 PRIMARY KEY(period_id,bot_id,game_mode,rules_version)
);
-- Reuse the immutable-history trigger without modifying any applied migration.
CREATE TRIGGER immutable_scoring BEFORE UPDATE OR DELETE ON match_scoring_ledger FOR EACH ROW EXECUTE FUNCTION reject_version_change();
CREATE TRIGGER immutable_rating_events BEFORE UPDATE OR DELETE ON rating_events FOR EACH ROW EXECUTE FUNCTION reject_version_change();
CREATE TRIGGER immutable_period_updates BEFORE UPDATE OR DELETE ON rating_period_updates FOR EACH ROW EXECUTE FUNCTION reject_version_change();
