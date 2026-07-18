--liquibase formatted sql

--changeset agent-manager:065-extensions-version-column
--comment: E8 optimistic locking on extensions table. Concurrent PUTs against the same
--         extension row previously did silent last-write-wins (entity had no @Version).
--         Adds a version column with default 0 so JPA's @Version mechanism activates;
--         the second concurrent saver now hits ObjectOptimisticLockingFailureException,
--         which GlobalExceptionHandler translates to HTTP 409.
ALTER TABLE extensions
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
