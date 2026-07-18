--liquibase formatted sql

--changeset agm:014-team-improvements

-- Soft-delete / archive support for teams
ALTER TABLE teams ADD COLUMN IF NOT EXISTS archived BOOLEAN DEFAULT FALSE;
CREATE INDEX IF NOT EXISTS idx_teams_archived ON teams(archived);
