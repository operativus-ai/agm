--liquibase formatted sql

--changeset agentmanager:100-evaluation-runs-cascade-suite
--comment: Bug #43 — DELETE /api/v1/evaluations/suites/{id} 500'd on any suite that had executed evaluation_runs. The original CREATE TABLE evaluation_runs (001-schema.sql:236) declared the suite_id FK without ON DELETE CASCADE — RESTRICT by default — so deleteById tripped fk_evaluation_runs_suite and the generic exception handler returned 500. evaluation_cases already cascades (001-schema.sql:215), and evaluation_results cascades through evaluation_runs (256), so once we cascade the runs FK the whole tree drops cleanly.

ALTER TABLE evaluation_runs DROP CONSTRAINT IF EXISTS evaluation_runs_suite_id_fkey;

ALTER TABLE evaluation_runs ADD CONSTRAINT evaluation_runs_suite_id_fkey
    FOREIGN KEY (suite_id) REFERENCES evaluation_suites(id) ON DELETE CASCADE;
