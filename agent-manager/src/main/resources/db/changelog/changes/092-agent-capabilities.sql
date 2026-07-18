--liquibase formatted sql

--changeset agm:092-agent-capabilities runOnChange:false
--comment: DR-FR-7 capability vocabulary. Admins tag agents with short skill
--         labels (e.g. {"tax-questions","refund-disputes"}) which feed both
--         the LlmAgentClassifier prompt and the SemanticAgentScorer
--         embedding text. Native PostgreSQL TEXT[] (Hibernate handles via
--         columnDefinition); GIN index for future containment queries.
ALTER TABLE agents
    ADD COLUMN IF NOT EXISTS capabilities TEXT[] NOT NULL DEFAULT '{}';

CREATE INDEX IF NOT EXISTS idx_agent_capabilities
    ON agents USING GIN (capabilities);
