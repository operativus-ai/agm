--liquibase formatted sql

--changeset agentmanager:demo-006-runs-history context:"demo" runOnChange:true splitStatements:false
--comment: Synthetic run history — ~30 runs per active agent over the past 7 days, mixed status. Generated via CROSS JOIN with generate_series for compact representation.

INSERT INTO agent_runs (id, agent_id, session_id, user_id, org_id, input, output, status, created_at, updated_at)
SELECT
    'demo_run_' || a.agent_id || '_' || LPAD(gs.i::text, 3, '0'),
    a.agent_id,
    a.session_id,
    a.user_id,
    a.org_id,
    'Demo prompt ' || gs.i || ' for ' || a.agent_id,
    CASE
        WHEN gs.i % 20 = 0 THEN NULL
        WHEN gs.i % 17 = 0 THEN NULL
        ELSE 'Demo response ' || gs.i || ' from ' || a.agent_id || '. Generated for the demo seed.'
    END,
    CASE
        WHEN gs.i % 20 = 0 THEN 'FAILED'
        WHEN gs.i % 17 = 0 THEN 'CANCELLED'
        WHEN gs.i % 13 = 0 THEN 'BUDGET_HALT'
        ELSE 'COMPLETED'
    END,
    NOW() - ((gs.i * 5) || ' hours')::interval,
    NOW() - ((gs.i * 5) || ' hours')::interval + INTERVAL '6 seconds'
FROM (
    VALUES
        ('demo_research_acme',  'demo_sess_001', 'd0000001-0000-0000-0000-000000000002'::varchar, 'DEMO_ACME'),
        ('demo_finance_acme',   'demo_sess_003', 'd0000001-0000-0000-0000-000000000001'::varchar, 'DEMO_ACME'),
        ('demo_support_acme',   'demo_sess_004', 'd0000001-0000-0000-0000-000000000003'::varchar, 'DEMO_ACME'),
        ('demo_writer_acme',    'demo_sess_005', 'd0000001-0000-0000-0000-000000000002'::varchar, 'DEMO_ACME'),
        ('demo_legal_acme',     'demo_sess_006', 'd0000001-0000-0000-0000-000000000001'::varchar, 'DEMO_ACME'),
        ('demo_support_globex', 'demo_sess_007', 'd0000001-0000-0000-0000-000000000004'::varchar, 'DEMO_GLOBEX'),
        ('demo_qa_globex',      'demo_sess_008', 'd0000001-0000-0000-0000-000000000004'::varchar, 'DEMO_GLOBEX'),
        ('demo_planner_globex', 'demo_sess_009', 'd0000001-0000-0000-0000-000000000004'::varchar, 'DEMO_GLOBEX')
) AS a(agent_id, session_id, user_id, org_id)
CROSS JOIN generate_series(1, 30) AS gs(i)
ON CONFLICT (id) DO NOTHING;
