--liquibase formatted sql

--changeset agentmanager:demo-011-schedules context:"demo" runOnChange:true
--comment: Scheduled agent runs — all seeded as is_active=FALSE so ScheduleExecutionPoller skips them. The demo profile has no provider_credentials rows for DEMO_ACME/DEMO_GLOBEX, so live execution would fail every poll tick with an ERROR log. Operators who want live scheduled-run behavior can flip is_active=TRUE after configuring provider credentials for the target org. Historical schedule_runs rows below are independent of is_active and continue to populate the UI recap.

INSERT INTO schedules (id, name, description, cron_expression, target_type, target_id, is_active, contextual_prompt, org_id, created_at, updated_at) VALUES
('demo_sched_hourly',  'Hourly market scan',      'Pulls overnight market headlines every hour during trading days.', '0 0 * * * *',  'AGENT', 'demo_research_acme',  FALSE, 'Summarize the top 3 financial-market headlines from the last 60 minutes.', 'DEMO_ACME',   NOW() - INTERVAL '30 days', NOW() - INTERVAL '1 hour'),
('demo_sched_daily',   'Daily compliance digest', 'Runs each weekday at 09:00 to compile a compliance digest.',        '0 0 9 * * 1-5','AGENT', 'demo_legal_acme',     FALSE, 'Produce a one-page digest of regulatory updates from the past 24 hours.', 'DEMO_ACME',   NOW() - INTERVAL '20 days', NOW() - INTERVAL '4 days'),
('demo_sched_weekly',  'Weekly support recap',    'Mondays 08:00 — summarizes last week''s support volume by topic.',  '0 0 8 * * 1',  'AGENT', 'demo_support_globex', FALSE, 'Aggregate last week''s support tickets by topic and produce a recap.',     'DEMO_GLOBEX', NOW() - INTERVAL '15 days', NOW() - INTERVAL '2 days')
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    cron_expression = EXCLUDED.cron_expression,
    is_active = EXCLUDED.is_active,
    target_id = EXCLUDED.target_id,
    org_id = EXCLUDED.org_id,
    updated_at = CURRENT_TIMESTAMP;

--changeset agentmanager:demo-011-schedule-runs context:"demo" runOnChange:true splitStatements:false
--comment: Recent schedule executions so the scheduler page shows recorded history.

DELETE FROM schedule_runs WHERE id LIKE 'demo_schedrun_%';

INSERT INTO schedule_runs (id, schedule_id, status, started_at, completed_at, output)
SELECT
    'demo_schedrun_' || s.schedule_id || '_' || LPAD(gs.i::text, 3, '0'),
    s.schedule_id,
    CASE WHEN gs.i % 12 = 0 THEN 'FAILED' ELSE 'COMPLETED' END,
    NOW() - ((gs.i * s.hour_step) || ' hours')::interval,
    NOW() - ((gs.i * s.hour_step) || ' hours')::interval + INTERVAL '5 seconds',
    CASE WHEN gs.i % 12 = 0
        THEN '{"seed":"demo-v1","error":"upstream-timeout"}'::jsonb
        ELSE ('{"seed":"demo-v1","run":' || gs.i || '}')::jsonb
    END
FROM (VALUES
    ('demo_sched_hourly',   1),
    ('demo_sched_daily',   24),
    ('demo_sched_weekly', 168)
) AS s(schedule_id, hour_step)
CROSS JOIN generate_series(1, 12) AS gs(i);
