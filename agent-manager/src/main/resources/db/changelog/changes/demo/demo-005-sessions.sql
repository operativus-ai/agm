--liquibase formatted sql

--changeset agentmanager:demo-005-sessions context:"demo" runOnChange:true
--comment: Demo conversation sessions. agent_runs and agent_messages reference these. session_state kept minimal.

INSERT INTO agent_sessions (session_id, user_id, org_id, agent_id, title, session_state, created_at, updated_at) VALUES
('demo_sess_001', 'd0000001-0000-0000-0000-000000000002', 'DEMO_ACME',   'demo_research_acme', 'Q4 competitor scan',           '{"seed":"demo-v1"}', NOW() - INTERVAL '3 days',  NOW() - INTERVAL '3 days'),
('demo_sess_002', 'd0000001-0000-0000-0000-000000000002', 'DEMO_ACME',   'demo_research_acme', 'API gateway pricing research', '{"seed":"demo-v1"}', NOW() - INTERVAL '2 days',  NOW() - INTERVAL '2 days'),
('demo_sess_003', 'd0000001-0000-0000-0000-000000000001', 'DEMO_ACME',   'demo_finance_acme',  'Quarterly burn-rate review',   '{"seed":"demo-v1"}', NOW() - INTERVAL '5 days',  NOW() - INTERVAL '5 days'),
('demo_sess_004', 'd0000001-0000-0000-0000-000000000003', 'DEMO_ACME',   'demo_support_acme',  'Refund policy lookup',         '{"seed":"demo-v1"}', NOW() - INTERVAL '1 day',   NOW() - INTERVAL '1 day'),
('demo_sess_005', 'd0000001-0000-0000-0000-000000000002', 'DEMO_ACME',   'demo_writer_acme',   'Release notes v2.4',           '{"seed":"demo-v1"}', NOW() - INTERVAL '4 days',  NOW() - INTERVAL '4 days'),
('demo_sess_006', 'd0000001-0000-0000-0000-000000000001', 'DEMO_ACME',   'demo_legal_acme',    'NDA review — Vendor A',        '{"seed":"demo-v1"}', NOW() - INTERVAL '6 days',  NOW() - INTERVAL '6 days'),
('demo_sess_007', 'd0000001-0000-0000-0000-000000000004', 'DEMO_GLOBEX', 'demo_support_globex','Account login issue #4521',    '{"seed":"demo-v1"}', NOW() - INTERVAL '12 hours',NOW() - INTERVAL '12 hours'),
('demo_sess_008', 'd0000001-0000-0000-0000-000000000004', 'DEMO_GLOBEX', 'demo_support_globex','Billing dispute #4522',        '{"seed":"demo-v1"}', NOW() - INTERVAL '8 hours', NOW() - INTERVAL '8 hours'),
('demo_sess_009', 'd0000001-0000-0000-0000-000000000004', 'DEMO_GLOBEX', 'demo_planner_globex','Migration plan Q1 2026',       '{"seed":"demo-v1"}', NOW() - INTERVAL '3 days',  NOW() - INTERVAL '3 days')
ON CONFLICT (session_id) DO UPDATE SET
    title = EXCLUDED.title,
    session_state = EXCLUDED.session_state,
    updated_at = EXCLUDED.updated_at;

INSERT INTO agent_messages (id, session_id, message_type, content, metadata, created_at) VALUES
    ('11111111-1111-1111-1111-000000000001', 'demo_sess_001', 'USER',      'Summarize how Anthropic, OpenAI and Google price their flagship models.', '{"seed":"demo-v1"}', NOW() - INTERVAL '3 days'),
    ('11111111-1111-1111-1111-000000000002', 'demo_sess_001', 'ASSISTANT', 'Anthropic charges $3/$15 per 1M tokens for Claude 3.5 Sonnet; OpenAI $2.50/$10 for GPT-4o; Google $1.25/$10 for Gemini 2.5 Pro. See attached citations.', '{"seed":"demo-v1"}', NOW() - INTERVAL '3 days' + INTERVAL '8 seconds'),
    ('11111111-1111-1111-1111-000000000003', 'demo_sess_003', 'USER',      'What was our Q3 LLM spend vs the same period last year?', '{"seed":"demo-v1"}', NOW() - INTERVAL '5 days'),
    ('11111111-1111-1111-1111-000000000004', 'demo_sess_003', 'ASSISTANT', 'Q3 2026 LLM spend was $42,180 vs $28,940 in Q3 2025, a 45.8% YoY increase. Anomaly: Gemini usage up 3.2x.', '{"seed":"demo-v1"}', NOW() - INTERVAL '5 days' + INTERVAL '12 seconds'),
    ('11111111-1111-1111-1111-000000000005', 'demo_sess_007', 'USER',      'I cannot log in after a password reset.', '{"seed":"demo-v1"}', NOW() - INTERVAL '12 hours'),
    ('11111111-1111-1111-1111-000000000006', 'demo_sess_007', 'ASSISTANT', 'Try clearing the OAuth cookie at /.auth/clear and retry. If that fails, escalate per the Tier-1 runbook.', '{"seed":"demo-v1"}', NOW() - INTERVAL '12 hours' + INTERVAL '4 seconds')
ON CONFLICT (id) DO NOTHING;
