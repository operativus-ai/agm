--liquibase formatted sql

--changeset agentmanager:102-password-reset-tokens
--comment: Self-serve password reset (Phase 1 #7). Stores short-lived bearer tokens emailed to users so they can set a new password without operator intervention. Token rows are insert-once / consume-once — once used or expired, the row stays but is no longer accepted. The retention sweep keeps consumed/expired rows for 30 days so an operator can investigate "did this reset really happen" via audit lookup.

-- 1. Token table. token_hash is sha256 of the raw token; we never store the raw form.
--    user_id references users(id) with ON DELETE CASCADE so a deleted user's reset
--    tokens auto-vanish (no stranded rows that could grant reset access to a recycled
--    username, however unlikely).
CREATE TABLE IF NOT EXISTS password_reset_tokens (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    token_hash      VARCHAR(64)  NOT NULL UNIQUE,
    user_id         UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    requested_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ  NOT NULL,
    consumed_at     TIMESTAMPTZ          NULL,
    requester_ip    VARCHAR(64)          NULL,
    requester_ua    VARCHAR(512)         NULL
);

-- 2. Lookup index: every confirm hits this exact key. token_hash is already UNIQUE so
--    Postgres has a backing index — this is redundant but explicit for the query
--    planner. Skip; the UNIQUE index suffices.

-- 3. Per-user scan index for the rate-limit check (at most N requests/hour per user).
CREATE INDEX IF NOT EXISTS ix_password_reset_tokens_user_requested_at
    ON password_reset_tokens (user_id, requested_at DESC);

-- 4. Retention sweep index — DataRetentionService walks this on its schedule.
CREATE INDEX IF NOT EXISTS ix_password_reset_tokens_expires_at
    ON password_reset_tokens (expires_at);
