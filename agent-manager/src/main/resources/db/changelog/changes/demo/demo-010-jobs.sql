--liquibase formatted sql

--changeset agentmanager:demo-010-jobs context:"demo" runOnChange:true splitStatements:false
--comment: Background jobs — completed, failed, and one paused for queue admin demos.

DELETE FROM background_jobs WHERE id LIKE 'demo_job_%';

-- 18 completed jobs
INSERT INTO background_jobs (id, agent_id, job_type, payload, status, retry_count, max_retries, priority, created_at, started_at, completed_at, result)
SELECT
    'demo_job_completed_' || LPAD(gs.i::text, 3, '0'),
    CASE gs.i % 3
        WHEN 0 THEN 'demo_research_acme'
        WHEN 1 THEN 'demo_writer_acme'
        ELSE 'demo_support_globex'
    END,
    CASE gs.i % 4
        WHEN 0 THEN 'AGENT_RUN'
        WHEN 1 THEN 'KNOWLEDGE_INGESTION'
        WHEN 2 THEN 'WORKFLOW_EXECUTION'
        ELSE 'EVALUATION_RUN'
    END,
    '{"seed":"demo-v1","attempt":1}',
    'COMPLETED',
    0,
    3,
    CASE gs.i % 3 WHEN 0 THEN 'HIGH' WHEN 1 THEN 'NORMAL' ELSE 'LOW' END,
    NOW() - ((gs.i * 4) || ' hours')::interval,
    NOW() - ((gs.i * 4) || ' hours')::interval + INTERVAL '2 seconds',
    NOW() - ((gs.i * 4) || ' hours')::interval + INTERVAL '8 seconds',
    'Demo job ' || gs.i || ' completed successfully.'
FROM generate_series(1, 18) AS gs(i);

-- 1 failed job (max retries exhausted, DLQ)
INSERT INTO background_jobs (id, agent_id, job_type, payload, status, retry_count, max_retries, error_message, priority, created_at, started_at, completed_at) VALUES
('demo_job_failed_001', 'demo_finance_acme', 'AGENT_RUN', '{"seed":"demo-v1"}', 'FAILED', 3, 3, 'Upstream model timeout after 3 retries (demo)', 'NORMAL', NOW() - INTERVAL '6 hours', NOW() - INTERVAL '6 hours' + INTERVAL '2 seconds', NOW() - INTERVAL '5 hours');

-- 1 paused job
INSERT INTO background_jobs (id, agent_id, job_type, payload, status, retry_count, max_retries, priority, created_at, started_at) VALUES
('demo_job_paused_001', 'demo_legal_acme', 'AGENT_RUN', '{"seed":"demo-v1","reason":"HITL_PENDING"}', 'PAUSED', 0, 3, 'NORMAL', NOW() - INTERVAL '45 minutes', NOW() - INTERVAL '44 minutes');

-- 1 queued job (waiting to run)
INSERT INTO background_jobs (id, agent_id, job_type, payload, status, retry_count, max_retries, priority, created_at) VALUES
('demo_job_queued_001', 'demo_research_acme', 'AGENT_RUN', '{"seed":"demo-v1"}', 'QUEUED', 0, 3, 'NORMAL', NOW() - INTERVAL '2 minutes');
