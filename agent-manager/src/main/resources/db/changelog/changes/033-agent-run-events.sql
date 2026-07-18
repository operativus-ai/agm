--liquibase formatted sql

--changeset agm:033-agent-run-events-table splitStatements:false
CREATE TABLE IF NOT EXISTS agent_run_events (
    id BIGSERIAL PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    run_id VARCHAR(255) NOT NULL,
    agent_id VARCHAR(255),
    parent_run_id VARCHAR(255),
    session_id VARCHAR(255),
    org_id VARCHAR(255),
    orchestration_depth INTEGER,
    payload JSONB,
    event_ts TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

--changeset agm:033-agent-run-events-index-run
CREATE INDEX IF NOT EXISTS idx_agent_run_events_run_id
    ON agent_run_events (run_id, event_ts ASC);

--changeset agm:033-agent-run-events-index-org-ts
CREATE INDEX IF NOT EXISTS idx_agent_run_events_org_ts
    ON agent_run_events (org_id, event_ts DESC);

--changeset agm:033-agent-run-events-index-session
CREATE INDEX IF NOT EXISTS idx_agent_run_events_session_id
    ON agent_run_events (session_id);

--changeset agm:033-agent-run-events-index-event-type
CREATE INDEX IF NOT EXISTS idx_agent_run_events_event_type
    ON agent_run_events (event_type, event_ts DESC);
