--liquibase formatted sql

--changeset agentmanager:demo-007-a2a-peers context:"demo" runOnChange:true
--comment: Cross-org A2A peer registration — ACME registers GLOBEX as a remote peer.

INSERT INTO a2a_remote_agents (id, remote_agent_id, base_url, alias, outbound_api_key, security_tier, trusted, last_verified_at, registered_by, created_at, updated_at) VALUES
('demo_peer_globex', 'demo_support_globex', 'http://localhost:8080', 'globex-support', NULL, 1, TRUE, NOW() - INTERVAL '5 days', 'demo-admin@operativus.test', NOW() - INTERVAL '7 days', NOW() - INTERVAL '5 days')
ON CONFLICT (id) DO UPDATE SET
    remote_agent_id = EXCLUDED.remote_agent_id,
    base_url = EXCLUDED.base_url,
    alias = EXCLUDED.alias,
    last_verified_at = EXCLUDED.last_verified_at,
    updated_at = CURRENT_TIMESTAMP;

--changeset agentmanager:demo-007-a2a-task-events context:"demo" runOnChange:true splitStatements:false
--comment: A2A task lifecycle events — mix of completed and one paused/in-progress for the panel.

-- Wipe previous demo task events (id is BIGSERIAL, so we identify them via trace_id sentinel)
DELETE FROM a2a_task_events WHERE trace_id LIKE 'demo-trace-%';

-- 8 completed tasks (SUBMITTED → WORKING → COMPLETED for each task_id)
INSERT INTO a2a_task_events (task_id, run_id, target_agent_id, initiating_agent, session_id, trace_id, status, message, event_ts)
SELECT
    'demo_task_' || LPAD(gs.i::text, 3, '0') AS task_id,
    NULL                                       AS run_id,
    'demo_research_acme'                       AS target_agent_id,
    'demo-java-agent'                          AS initiating_agent,
    NULL                                       AS session_id,
    'demo-trace-' || LPAD(gs.i::text, 3, '0')  AS trace_id,
    s.status                                   AS status,
    'Demo A2A task ' || gs.i || ' — ' || s.status AS message,
    NOW() - ((gs.i * 6) || ' hours')::interval + s.offset_interval
FROM generate_series(1, 8) AS gs(i)
CROSS JOIN (VALUES
    ('SUBMITTED'::varchar, INTERVAL '0 seconds'),
    ('WORKING'::varchar,   INTERVAL '1 second'),
    ('COMPLETED'::varchar, INTERVAL '7 seconds')
) AS s(status, offset_interval);

-- 1 paused task (mid-flight) — SUBMITTED + WORKING + PAUSED, no COMPLETED.
INSERT INTO a2a_task_events (task_id, run_id, target_agent_id, initiating_agent, trace_id, status, message, event_ts) VALUES
('demo_task_paused', NULL, 'demo_finance_acme', 'demo-java-agent', 'demo-trace-paused', 'SUBMITTED', 'Awaiting human review',         NOW() - INTERVAL '30 minutes'),
('demo_task_paused', NULL, 'demo_finance_acme', 'demo-java-agent', 'demo-trace-paused', 'WORKING',   'Started; reached tool gate',    NOW() - INTERVAL '29 minutes'),
('demo_task_paused', NULL, 'demo_finance_acme', 'demo-java-agent', 'demo-trace-paused', 'PAUSED',    'HITL approval required: TRANSFER_FUNDS', NOW() - INTERVAL '28 minutes');
