--liquibase formatted sql
--changeset agm:055-background-jobs-cancel-columns

ALTER TABLE background_jobs
    ADD COLUMN cancel_reason VARCHAR(255),
    ADD COLUMN cancelled_by_user_id VARCHAR(255);
