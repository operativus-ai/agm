--liquibase formatted sql

--changeset agentmanager:demo-012-hitl-pending context:"demo" runOnChange:true splitStatements:false
--comment: Human-Review pending approvals. Two on TIER_2_STRICT demo_finance_acme so the HITL panel has live work.

DELETE FROM human_review_pending WHERE id LIKE 'demo_hrp_%';

INSERT INTO human_review_pending (id, run_id, subject_type, subject_id, reason, options, org_id, created_at, expires_at, decided_at, decision, decided_by, payload) VALUES
-- Pending: AGENT_TOOL_CALL (a destructive tool call awaiting approval)
('demo_hrp_001', 'demo_run_demo_finance_acme_001', 'AGENT_TOOL_CALL', 'transferFunds', 'Tool transferFunds requires approval per TIER_2_STRICT policy.',
    '{"seed":"demo-v1","tool":"transferFunds","arguments":{"amount":2500,"account":"ACME-OPS-001"}}'::jsonb,
    'DEMO_ACME', NOW() - INTERVAL '20 minutes', NOW() + INTERVAL '4 hours', NULL, NULL, NULL,
    '{"agent_id":"demo_finance_acme","session_id":"demo_sess_003"}'::jsonb),

-- Pending: TEAM_MEMBER_DISPATCH (router about to dispatch to a member agent)
('demo_hrp_002', 'demo_run_demo_router_acme_001', 'TEAM_MEMBER_DISPATCH', 'demo_legal_acme', 'Dispatching to demo_legal_acme requires approval — TIER_3_REGULATED member.',
    '{"seed":"demo-v1","dispatching_to":"demo_legal_acme","cursor":{"step":2}}'::jsonb,
    'DEMO_ACME', NOW() - INTERVAL '15 minutes', NOW() + INTERVAL '6 hours', NULL, NULL, NULL,
    '{"team_id":"demo_team_router_acme"}'::jsonb),

-- Already decided (APPROVED) — shows historic context next to pending ones
('demo_hrp_003', 'demo_run_demo_finance_acme_002', 'AGENT_TOOL_CALL', 'sendEmail', 'Outbound email tool requires approval.',
    '{"seed":"demo-v1","tool":"sendEmail","arguments":{"to":"ops@operativus.test","subject":"Q4 review"}}'::jsonb,
    'DEMO_ACME', NOW() - INTERVAL '2 days', NOW() + INTERVAL '4 hours', NOW() - INTERVAL '2 days' + INTERVAL '14 minutes', 'APPROVED', 'demo-admin',
    '{"agent_id":"demo_finance_acme","session_id":"demo_sess_003"}'::jsonb),

-- Already decided (REJECTED)
('demo_hrp_004', 'demo_run_demo_legal_acme_003', 'AGENT_TOOL_CALL', 'publishDocument', 'Publishing tool gated by legal policy.',
    '{"seed":"demo-v1","tool":"publishDocument","arguments":{"doc_id":"contract-2026-01"}}'::jsonb,
    'DEMO_ACME', NOW() - INTERVAL '3 days', NOW() - INTERVAL '3 days' + INTERVAL '4 hours', NOW() - INTERVAL '3 days' + INTERVAL '8 minutes', 'REJECTED', 'demo-admin',
    '{"agent_id":"demo_legal_acme"}'::jsonb);
