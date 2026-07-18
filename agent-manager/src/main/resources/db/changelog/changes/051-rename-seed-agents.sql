--liquibase formatted sql

--changeset agentmanager:003-rename-seed-agents splitStatements:false
--comment: One-time cleanup — removes legacy seed agent IDs (procurator_assistant, finance_agent)
--         that were renamed to (assistant, document_writer) in changeset 002-seed-data.
--         New rows are already inserted by the runOnChange:true 002-seed-data changeset.
--         All FK-dependent rows for the old IDs are deleted first; agent_tools cascades automatically.
--         Safe to run on fresh installs: the DO block is a no-op when old IDs are absent.

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM agents WHERE id IN ('procurator_assistant', 'finance_agent')) THEN

        -- Tables with FK to agents(id) that do NOT have ON DELETE CASCADE (ordered
        -- leaf-to-root so child records are removed before their parents).
        DELETE FROM agent_reflections   WHERE agent_id IN ('procurator_assistant', 'finance_agent');
        DELETE FROM agent_runs          WHERE agent_id IN ('procurator_assistant', 'finance_agent');
        DELETE FROM agent_sessions      WHERE agent_id IN ('procurator_assistant', 'finance_agent');
        DELETE FROM a2a_task_events     WHERE agent_id IN ('procurator_assistant', 'finance_agent');
        DELETE FROM agentic_memories    WHERE agent_id IN ('procurator_assistant', 'finance_agent');
        DELETE FROM threat_events       WHERE agent_id IN ('procurator_assistant', 'finance_agent');
        DELETE FROM sandbox_capabilities WHERE agent_id IN ('procurator_assistant', 'finance_agent');
        DELETE FROM evaluation_results  WHERE run_id IN (
            SELECT id FROM evaluation_runs WHERE agent_id IN ('procurator_assistant', 'finance_agent'));
        DELETE FROM evaluation_runs     WHERE agent_id IN ('procurator_assistant', 'finance_agent');
        DELETE FROM evaluations         WHERE agent_id IN ('procurator_assistant', 'finance_agent');
        DELETE FROM team_members        WHERE agent_id IN ('procurator_assistant', 'finance_agent');
        DELETE FROM workflow_steps      WHERE agent_id IN ('procurator_assistant', 'finance_agent');
        DELETE FROM approvals           WHERE agent_id IN ('procurator_assistant', 'finance_agent');
        DELETE FROM agent_audits        WHERE agent_id IN ('procurator_assistant', 'finance_agent');

        -- Update investment_team members list if it still references finance_agent.
        UPDATE agents
           SET members = REPLACE(members::text, '"finance_agent"', '"document_writer"')::jsonb::text
         WHERE id = 'investment_team'
           AND members::text LIKE '%finance_agent%';

        -- Delete the old agent rows (agent_tools cascades automatically).
        DELETE FROM agents WHERE id IN ('procurator_assistant', 'finance_agent');

        RAISE NOTICE '003-rename-seed-agents: legacy agent IDs removed.';
    ELSE
        RAISE NOTICE '003-rename-seed-agents: old IDs not present, nothing to do.';
    END IF;
END $$;
