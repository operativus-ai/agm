--liquibase formatted sql

-- AGM logging plan §5.21 follow-up — promote fk_agent_reflections_run from
-- NOT VALID (added in changeset 016) to validated, so every reflection row is
-- enforced against agent_runs, not just new inserts.
--
-- Runs as two changesets so they track independently:
--   037a: delete orphan reflections (unreachable rows that predate the FK or
--         survived a non-ordered DELETE FROM agent_runs).
--   037b: VALIDATE CONSTRAINT once the table is known to be clean.

--changeset agm:037a-delete-orphan-agent-reflections
DELETE FROM agent_reflections
 WHERE NOT EXISTS (SELECT 1 FROM agent_runs ar WHERE ar.id = agent_reflections.run_id);
--rollback SELECT 1;

--changeset agm:037b-validate-agent-reflections-fk
ALTER TABLE agent_reflections VALIDATE CONSTRAINT fk_agent_reflections_run;
--rollback ALTER TABLE agent_reflections DROP CONSTRAINT fk_agent_reflections_run;
--rollback ALTER TABLE agent_reflections ADD CONSTRAINT fk_agent_reflections_run FOREIGN KEY (run_id) REFERENCES agent_runs(id) NOT VALID;
