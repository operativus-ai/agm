--liquibase formatted sql
--changeset agm:047-models-rate-limit-rpm
ALTER TABLE models
    ADD COLUMN IF NOT EXISTS rate_limit_rpm INTEGER;
