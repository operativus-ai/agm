--liquibase formatted sql

--changeset agm:040-models-available-column
-- §7 Model Pinger Part 1: nullable BOOLEAN reflecting the most recent liveness probe outcome
-- from ModelAvailabilityPoller. Nullable so existing rows pre-poll show "unknown" rather
-- than a misleading false. last_pinged_at is the staleness anchor for the UI badge.
ALTER TABLE models
    ADD COLUMN IF NOT EXISTS available BOOLEAN,
    ADD COLUMN IF NOT EXISTS last_pinged_at TIMESTAMP;
--rollback ALTER TABLE models DROP COLUMN IF EXISTS available, DROP COLUMN IF EXISTS last_pinged_at;
