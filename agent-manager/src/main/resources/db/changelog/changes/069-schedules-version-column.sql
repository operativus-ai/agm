--liquibase formatted sql

--changeset agent-manager:069-schedules-version-column
--comment: F3 optimistic locking on schedules table. Concurrent PUTs against the same
--         schedule row previously did silent last-write-wins (entity had no @Version).
--         Adds a version column with default 0 so JPA's @Version mechanism activates;
--         the second concurrent saver now hits ObjectOptimisticLockingFailureException,
--         which GlobalExceptionHandler translates to HTTP 409. Mirrors changeset 065
--         (extensions). Service layer also performs a client-known-version pre-check
--         that surfaces the conflict before the DB-level race.
ALTER TABLE schedules
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
