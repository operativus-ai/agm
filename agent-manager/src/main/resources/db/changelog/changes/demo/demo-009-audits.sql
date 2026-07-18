--liquibase formatted sql

--changeset agentmanager:demo-009-system-audits context:"demo" runOnChange:true splitStatements:false
--comment: 200+ system_audits rows so the audit dashboard has depth. Spans login/access/admin actions across both orgs.

-- Wipe previous demo system_audits before reseeding (idempotent re-run support).
DELETE FROM system_audits WHERE id LIKE 'demo_sysaudit_%';

INSERT INTO system_audits (id, org_id, username, action, resource_type, resource_id, http_method, request_path, response_status, created_at)
SELECT
    'demo_sysaudit_' || LPAD(gs.i::text, 4, '0'),
    CASE WHEN gs.i % 3 = 0 THEN 'DEMO_GLOBEX' ELSE 'DEMO_ACME' END,
    CASE
        WHEN gs.i % 4 = 0 THEN 'demo-admin'
        WHEN gs.i % 4 = 1 THEN 'demo-analyst'
        WHEN gs.i % 4 = 2 THEN 'demo-viewer'
        ELSE 'demo-ops'
    END,
    CASE gs.i % 6
        WHEN 0 THEN 'LOGIN_SUCCESS'
        WHEN 1 THEN 'AGENT_RUN'
        WHEN 2 THEN 'AGENT_UPDATE'
        WHEN 3 THEN 'KB_INGEST'
        WHEN 4 THEN 'EVAL_TRIGGER'
        ELSE 'CONFIG_READ'
    END,
    CASE gs.i % 4
        WHEN 0 THEN 'AUTH'
        WHEN 1 THEN 'AGENT'
        WHEN 2 THEN 'KNOWLEDGE_BASE'
        ELSE 'EVALUATION'
    END,
    'demo-resource-' || gs.i,
    CASE gs.i % 3 WHEN 0 THEN 'GET' WHEN 1 THEN 'POST' ELSE 'PUT' END,
    '/api/demo/path/' || gs.i,
    CASE WHEN gs.i % 25 = 0 THEN 403 WHEN gs.i % 30 = 0 THEN 500 ELSE 200 END,
    NOW() - ((gs.i * 30) || ' minutes')::interval
FROM generate_series(1, 220) AS gs(i);

--changeset agentmanager:demo-009-agent-audits context:"demo" runOnChange:true splitStatements:false
--comment: agent_audits rows. Immutability trigger blocks UPDATE/DELETE; INSERTs are fine. Skip rollback — deletes require GUC bypass.

-- set_config with is_local=true inside a DO $$ block reverts when the block exits,
-- so the bypass doesn't reach the DELETE row trigger. Use session-scope (is_local=false)
-- directly as a SELECT so the setting persists across the subsequent DELETE statement.
SELECT set_config('agm.audit_immutability_bypass', 'true', false);
DELETE FROM agent_audits WHERE id LIKE 'demo_agentaudit_%';
SELECT set_config('agm.audit_immutability_bypass', 'false', false);

-- Bug #51: agent_audits.org_id was previously omitted from the INSERT, leaving
-- all 72 demo rows with org_id=NULL. AgentAuditRepository.search filters by
-- a.orgId = :orgId, so NULL rows were silently excluded from every tenant query.
-- Derive org_id from the seeded agents table (demo-003-agents.sql runs first).
INSERT INTO agent_audits (id, agent_id, org_id, action, username, changeset, created_at)
SELECT
    'demo_agentaudit_' || LPAD(gs.i::text, 4, '0'),
    a.agent_id,
    ag.org_id,
    CASE gs.i % 3 WHEN 0 THEN 'CREATE' WHEN 1 THEN 'UPDATE' ELSE 'TOOL_GRANT' END,
    CASE WHEN gs.i % 2 = 0 THEN 'demo-admin' ELSE 'demo-analyst' END,
    ('{"seed":"demo-v1","change":"field_' || gs.i || '"}')::jsonb,
    NOW() - ((gs.i * 90) || ' minutes')::interval
FROM (VALUES
    ('demo_research_acme'),
    ('demo_finance_acme'),
    ('demo_support_acme'),
    ('demo_writer_acme'),
    ('demo_legal_acme'),
    ('demo_router_acme'),
    ('demo_support_globex'),
    ('demo_qa_globex'),
    ('demo_planner_globex')
) AS a(agent_id)
JOIN agents ag ON ag.id = a.agent_id
CROSS JOIN generate_series(1, 8) AS gs(i)
ON CONFLICT (id) DO NOTHING;
