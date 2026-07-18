--liquibase formatted sql

--changeset agm:039-sse-tokens-table
-- OBS-T005: backing table for the Postgres-cluster fallback SseTokenStore impl. The Caffeine
-- and Redis impls don't touch this table; only PostgresSseTokenStore reads/writes it.
-- expires_at is a wall-clock instant; the DataRetentionService cleanup pass GCs past-expiry
-- rows on its existing daily schedule.
CREATE TABLE IF NOT EXISTS sse_tokens (
    id          UUID PRIMARY KEY,
    run_id      VARCHAR(255) NOT NULL,
    user_id     VARCHAR(255) NOT NULL,
    org_id      VARCHAR(255),
    authorities TEXT NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL
);
--rollback DROP TABLE IF EXISTS sse_tokens;

--changeset agm:039-idx-sse-tokens-expires-at
-- Drives DataRetentionService's "DELETE FROM sse_tokens WHERE expires_at < NOW()" sweep.
-- Range scan, never a seq scan even after the table accumulates churn.
CREATE INDEX IF NOT EXISTS idx_sse_tokens_expires_at
    ON sse_tokens (expires_at);
--rollback DROP INDEX IF EXISTS idx_sse_tokens_expires_at;
