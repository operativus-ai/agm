--liquibase formatted sql

-- ============================================================
-- 016: Missing indexes on high-traffic filter columns
--      and FK constraints for referential integrity.
--
-- All FKs use NOT VALID to skip scanning existing rows —
-- safe to apply on live data. Run VALIDATE CONSTRAINT
-- separately during a maintenance window if enforcement
-- against historical rows is required.
-- ============================================================

-- ============================================================
-- INDEXES: agent_runs
-- Queried constantly by agent_id (per-agent run history),
-- status (polling for QUEUED/RUNNING), and org_id (tenant filter).
-- ============================================================
--changeset agm:016-idx-agent-runs
CREATE INDEX IF NOT EXISTS idx_agent_runs_agent_id  ON agent_runs(agent_id);
CREATE INDEX IF NOT EXISTS idx_agent_runs_status     ON agent_runs(status);
CREATE INDEX IF NOT EXISTS idx_agent_runs_org_id     ON agent_runs(org_id);

-- ============================================================
-- INDEXES: agent_sessions
-- agent_id is unindexed despite being a primary filter when
-- loading sessions for a given agent.
-- ============================================================
--changeset agm:016-idx-agent-sessions
CREATE INDEX IF NOT EXISTS idx_agent_sessions_agent_id ON agent_sessions(agent_id);

-- ============================================================
-- INDEXES: agent_reflections
-- No indexes exist on this table at all. run_id, session_id,
-- and agent_id are all common lookup keys.
-- ============================================================
--changeset agm:016-idx-agent-reflections
CREATE INDEX IF NOT EXISTS idx_agent_reflections_run_id     ON agent_reflections(run_id);
CREATE INDEX IF NOT EXISTS idx_agent_reflections_session_id ON agent_reflections(session_id);
CREATE INDEX IF NOT EXISTS idx_agent_reflections_agent_id   ON agent_reflections(agent_id);

-- ============================================================
-- INDEXES: agentic_memories
-- user_id is NOT NULL and the primary dimension for memory
-- retrieval. agent_id is the secondary filter.
-- ============================================================
--changeset agm:016-idx-agentic-memories
CREATE INDEX IF NOT EXISTS idx_agentic_memories_user_id  ON agentic_memories(user_id);
CREATE INDEX IF NOT EXISTS idx_agentic_memories_agent_id ON agentic_memories(agent_id);

-- ============================================================
-- INDEXES: agentic_memory_outbox
-- status drives the outbox polling query (WHERE status = 'PENDING').
-- memory_id is the join key back to agentic_memories.
-- ============================================================
--changeset agm:016-idx-agentic-memory-outbox
CREATE INDEX IF NOT EXISTS idx_agentic_memory_outbox_status    ON agentic_memory_outbox(status);
CREATE INDEX IF NOT EXISTS idx_agentic_memory_outbox_memory_id ON agentic_memory_outbox(memory_id);

-- ============================================================
-- INDEXES: trace_spans
-- parent_id enables tree traversal (fetch all children of a span).
-- Without it every span lookup requires a full scan.
-- ============================================================
--changeset agm:016-idx-trace-spans
CREATE INDEX IF NOT EXISTS idx_trace_spans_parent_id ON trace_spans(parent_id);

-- ============================================================
-- INDEXES: threat_events
-- agent_id is the primary filter on the security dashboard.
-- ============================================================
--changeset agm:016-idx-threat-events
CREATE INDEX IF NOT EXISTS idx_threat_events_agent_id ON threat_events(agent_id);

-- ============================================================
-- INDEXES: sandbox_capabilities
-- agent_id is the only meaningful lookup key for this table.
-- ============================================================
--changeset agm:016-idx-sandbox-capabilities
CREATE INDEX IF NOT EXISTS idx_sandbox_capabilities_agent_id ON sandbox_capabilities(agent_id);

-- ============================================================
-- INDEXES: workflow_runs
-- session_id is used to find the active workflow for a session.
-- ============================================================
--changeset agm:016-idx-workflow-runs
CREATE INDEX IF NOT EXISTS idx_workflow_runs_session_id ON workflow_runs(session_id);

-- ============================================================
-- INDEXES: knowledge_contents
-- knowledge_base_id is unindexed. Every KB content fetch
-- (e.g. listing chunks for RAG ingestion) causes a full scan.
-- ============================================================
--changeset agm:016-idx-knowledge-contents
CREATE INDEX IF NOT EXISTS idx_knowledge_contents_knowledge_base_id ON knowledge_contents(knowledge_base_id);

-- ============================================================
-- FOREIGN KEYS
-- All use NOT VALID — constraint is enforced on new writes
-- immediately but existing rows are not scanned.
-- ============================================================

--changeset agm:016-fk-agent-runs
ALTER TABLE agent_runs
    ADD CONSTRAINT fk_agent_runs_agent
        FOREIGN KEY (agent_id) REFERENCES agents(id) NOT VALID;

ALTER TABLE agent_runs
    ADD CONSTRAINT fk_agent_runs_session
        FOREIGN KEY (session_id) REFERENCES agent_sessions(session_id) NOT VALID;

ALTER TABLE agent_runs
    ADD CONSTRAINT fk_agent_runs_parent
        FOREIGN KEY (parent_run_id) REFERENCES agent_runs(id) NOT VALID;

--changeset agm:016-fk-agent-sessions
ALTER TABLE agent_sessions
    ADD CONSTRAINT fk_agent_sessions_agent
        FOREIGN KEY (agent_id) REFERENCES agents(id) NOT VALID;

--changeset agm:016-fk-agent-reflections
ALTER TABLE agent_reflections
    ADD CONSTRAINT fk_agent_reflections_run
        FOREIGN KEY (run_id) REFERENCES agent_runs(id) NOT VALID;

ALTER TABLE agent_reflections
    ADD CONSTRAINT fk_agent_reflections_session
        FOREIGN KEY (session_id) REFERENCES agent_sessions(session_id) NOT VALID;

ALTER TABLE agent_reflections
    ADD CONSTRAINT fk_agent_reflections_agent
        FOREIGN KEY (agent_id) REFERENCES agents(id) NOT VALID;

--changeset agm:016-fk-agentic-memories
ALTER TABLE agentic_memories
    ADD CONSTRAINT fk_agentic_memories_agent
        FOREIGN KEY (agent_id) REFERENCES agents(id) NOT VALID;

ALTER TABLE agentic_memories
    ADD CONSTRAINT fk_agentic_memories_team
        FOREIGN KEY (team_id) REFERENCES teams(id) NOT VALID;

--changeset agm:016-fk-agentic-memory-outbox
ALTER TABLE agentic_memory_outbox
    ADD CONSTRAINT fk_agentic_memory_outbox_memory
        FOREIGN KEY (memory_id) REFERENCES agentic_memories(memory_id) NOT VALID;

--changeset agm:016-fk-threat-events
ALTER TABLE threat_events
    ADD CONSTRAINT fk_threat_events_agent
        FOREIGN KEY (agent_id) REFERENCES agents(id) NOT VALID;

--changeset agm:016-fk-trace-spans
ALTER TABLE trace_spans
    ADD CONSTRAINT fk_trace_spans_parent
        FOREIGN KEY (parent_id) REFERENCES trace_spans(id) NOT VALID;

--changeset agm:016-fk-sandbox-capabilities
ALTER TABLE sandbox_capabilities
    ADD CONSTRAINT fk_sandbox_capabilities_agent
        FOREIGN KEY (agent_id) REFERENCES agents(id) NOT VALID;

--changeset agm:016-fk-workflow-runs
ALTER TABLE workflow_runs
    ADD CONSTRAINT fk_workflow_runs_session
        FOREIGN KEY (session_id) REFERENCES agent_sessions(session_id) NOT VALID;

--changeset agm:016-fk-pii-audit-log
ALTER TABLE pii_audit_log
    ADD CONSTRAINT fk_pii_audit_log_session
        FOREIGN KEY (session_id) REFERENCES agent_sessions(session_id) NOT VALID;

--changeset agm:016-fk-evaluations-legacy
ALTER TABLE evaluations
    ADD CONSTRAINT fk_evaluations_agent
        FOREIGN KEY (agent_id) REFERENCES agents(id) NOT VALID;
