--liquibase formatted sql

--changeset agm:096-workflow-steps-drop-agent-fk runOnChange:false
--comment: Drop workflow_steps.agent_id → agents.id FK. The agent_id column is
--         repurposed by CONDITION and LOOP steps to carry the predicate / bounds
--         expression (e.g. "contains:dollar", "max:5|until:done"), not an agents
--         row id. The FK forced workarounds — existing runtime tests seeded
--         placeholder `agents` rows named with the expression string so the
--         INSERT could satisfy the FK. With the FK gone, AGENT/SEQUENTIAL/PARALLEL
--         steps still get agent-existence validation at the service layer (via
--         WorkflowService.addWorkflowStep + StepActionType discriminator);
--         CONDITION/LOOP store free-form expressions.
ALTER TABLE workflow_steps DROP CONSTRAINT IF EXISTS workflow_steps_agent_id_fkey;
--rollback ALTER TABLE workflow_steps ADD CONSTRAINT workflow_steps_agent_id_fkey FOREIGN KEY (agent_id) REFERENCES agents(id);
