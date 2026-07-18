--liquibase formatted sql
--changeset agm:049-teams-isolate-memory
ALTER TABLE teams
    ADD COLUMN IF NOT EXISTS isolate_memory BOOLEAN NOT NULL DEFAULT FALSE;
