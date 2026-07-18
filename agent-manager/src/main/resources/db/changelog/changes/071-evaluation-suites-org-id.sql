--liquibase formatted sql

--changeset agent-manager:071-evaluation-suites-org-id
--comment: G2 tenant-scope evaluation_suites. EvaluationController had no tenant
--         scoping anywhere — every read returned all rows across all orgs and
--         every mutation operated on any id regardless of ownership.
--         Adds org_id to the parent (suites); child paths (cases, runs, results)
--         enforce ownership via suite_id → suite.org_id checks at the controller.
--         Existing rows backfill to DEFAULT_SYSTEM_ORG so the default test caller
--         (which also lands in DEFAULT_SYSTEM_ORG) continues to see seeded suites.
ALTER TABLE evaluation_suites
    ADD COLUMN IF NOT EXISTS org_id TEXT NOT NULL DEFAULT 'DEFAULT_SYSTEM_ORG';

CREATE INDEX IF NOT EXISTS idx_evaluation_suites_org_id
    ON evaluation_suites(org_id);
