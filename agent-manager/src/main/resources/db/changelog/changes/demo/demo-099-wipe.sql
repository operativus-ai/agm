--liquibase formatted sql

--changeset agentmanager:demo-099-wipe context:"demo_wipe" runOnChange:true splitStatements:false
--comment: Idempotent teardown of every demo_* row across the whole graph. Runs only under the "demo_wipe" context — never automatically. Trigger explicitly with: ./mvnw liquibase:update -Dliquibase.contexts=demo_wipe

-- set_config with is_local=true inside a DO $$ block reverts when the block exits,
-- so the bypass doesn't reach the row triggers. Use session-scope (is_local=false)
-- directly as a SELECT so the setting persists across all subsequent DELETE statements.
SELECT set_config('agm.audit_immutability_bypass', 'true', false);

-- Reverse FK order: leaves first, roots last.
DELETE FROM vector_store         WHERE metadata->>'seed' = 'demo-v1';
DELETE FROM human_review_pending WHERE id         LIKE 'demo_hrp_%';
DELETE FROM schedule_runs        WHERE id         LIKE 'demo_schedrun_%';
DELETE FROM schedules            WHERE id         LIKE 'demo_sched_%';
DELETE FROM agent_messages       WHERE session_id LIKE 'demo_sess_%';
DELETE FROM evaluation_results   WHERE run_id     LIKE 'demo_eval_run_%';
DELETE FROM evaluation_runs      WHERE id         LIKE 'demo_eval_run_%';
DELETE FROM evaluation_cases     WHERE id         LIKE 'demo_eval_case_%';
DELETE FROM evaluation_suites    WHERE id         LIKE 'demo_eval_suite_%';
DELETE FROM a2a_task_events      WHERE trace_id   LIKE 'demo-trace-%';
DELETE FROM a2a_remote_agents    WHERE id         LIKE 'demo_peer_%';
DELETE FROM background_jobs      WHERE id         LIKE 'demo_job_%';
DELETE FROM agent_audits         WHERE id         LIKE 'demo_agentaudit_%';
DELETE FROM system_audits        WHERE id         LIKE 'demo_sysaudit_%';
DELETE FROM agent_runs           WHERE id         LIKE 'demo_run_%';
DELETE FROM team_members         WHERE team_id    LIKE 'demo_team_%';
DELETE FROM teams                WHERE id         LIKE 'demo_team_%';
DELETE FROM agent_sessions       WHERE session_id LIKE 'demo_sess_%';
DELETE FROM workflow_runs        WHERE workflow_id LIKE 'demo_wf_%';
DELETE FROM workflow_steps       WHERE workflow_id LIKE 'demo_wf_%';
DELETE FROM workflows            WHERE id         LIKE 'demo_wf_%';
DELETE FROM agents               WHERE id         LIKE 'demo_%';
DELETE FROM models               WHERE id         = 'demo_echo_model';
DELETE FROM knowledge_contents   WHERE knowledge_base_id IN (
    SELECT id FROM knowledge_bases WHERE id::text LIKE 'd0000002-%'
);
DELETE FROM knowledge_bases      WHERE id::text   LIKE 'd0000002-%';
DELETE FROM user_roles           WHERE user_id::text LIKE 'd0000001-%';
DELETE FROM users                WHERE username   LIKE 'demo-%';

SELECT set_config('agm.audit_immutability_bypass', 'false', false);
