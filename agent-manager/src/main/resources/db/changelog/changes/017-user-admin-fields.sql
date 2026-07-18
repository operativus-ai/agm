--liquibase formatted sql

--changeset agm:017-user-admin-fields
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS disabled      BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS last_login_at TIMESTAMPTZ;
