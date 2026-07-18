--liquibase formatted sql

--changeset agm:084-agents-fallback-model-ids runOnChange:false
--comment: gap #8 — adds fallback_model_ids JSONB column to agents. Stores an
--         ordered list of model ids tried in order when the primary
--         (agents.model_id) is rate-limited or quota-exceeded at call time.
--         Consumed by AgentClientFactory.buildChatClientForFallback via
--         AgentService.run / AgentStreamManager retry paths. Matches the
--         existing tools / members JSONB List<String> pattern.
ALTER TABLE agents
    ADD COLUMN IF NOT EXISTS fallback_model_ids JSONB;
