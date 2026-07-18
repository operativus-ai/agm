--liquibase formatted sql

--changeset agentmanager:demo-008-eval-suites context:"demo" runOnChange:true
--comment: Evaluation suites + cases for the evaluation dashboard. Two suites, ~5 cases each.

INSERT INTO evaluation_suites (id, name, description, created_by, created_at, updated_at) VALUES
('demo_eval_suite_factual', 'Factual Recall',          'Tests agents on factual recall of seeded KB content.',     'demo-admin@operativus.test', NOW() - INTERVAL '14 days', NOW() - INTERVAL '14 days'),
('demo_eval_suite_tone',    'Tone & Professionalism',  'Tests support agents for tone, brevity, and escalation.',  'demo-admin@operativus.test', NOW() - INTERVAL '10 days', NOW() - INTERVAL '10 days')
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO evaluation_cases (id, suite_id, name, input, expected_output, created_at, updated_at) VALUES
('demo_eval_case_f01', 'demo_eval_suite_factual', 'Pricing — Sonnet 3.5',  'What is the per-token price for Anthropic Claude 3.5 Sonnet input?', '$3 per 1M input tokens',                  NOW() - INTERVAL '14 days', NOW() - INTERVAL '14 days'),
('demo_eval_case_f02', 'demo_eval_suite_factual', 'Pricing — GPT-4o',      'What is the per-token price for GPT-4o input?',                       '$2.50 per 1M input tokens',               NOW() - INTERVAL '14 days', NOW() - INTERVAL '14 days'),
('demo_eval_case_f03', 'demo_eval_suite_factual', 'Pricing — Gemini Pro',  'What is the per-token output price for Gemini 2.5 Pro?',              '$10 per 1M output tokens',                NOW() - INTERVAL '14 days', NOW() - INTERVAL '14 days'),
('demo_eval_case_f04', 'demo_eval_suite_factual', 'Compliance — GDPR Art', 'Which GDPR article covers data subject access requests?',             'Article 15',                              NOW() - INTERVAL '14 days', NOW() - INTERVAL '14 days'),
('demo_eval_case_f05', 'demo_eval_suite_factual', 'Compliance — SOC 2',    'Name the five SOC 2 Trust Service Criteria.',                         'Security, Availability, Processing Integrity, Confidentiality, Privacy', NOW() - INTERVAL '14 days', NOW() - INTERVAL '14 days'),
('demo_eval_case_t01', 'demo_eval_suite_tone',    'Tone — Refund',         'I want my money back NOW.',                                           'Empathetic acknowledgement + policy reference + next step', NOW() - INTERVAL '10 days', NOW() - INTERVAL '10 days'),
('demo_eval_case_t02', 'demo_eval_suite_tone',    'Tone — Escalation',     'This is the third time I have called.',                              'Apology + immediate escalation + ticket number', NOW() - INTERVAL '10 days', NOW() - INTERVAL '10 days'),
('demo_eval_case_t03', 'demo_eval_suite_tone',    'Tone — Brevity',        'How do I reset my password?',                                         'Concise 3-step instruction without filler.', NOW() - INTERVAL '10 days', NOW() - INTERVAL '10 days')
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name,
    input = EXCLUDED.input,
    expected_output = EXCLUDED.expected_output,
    updated_at = CURRENT_TIMESTAMP;

--changeset agentmanager:demo-008-eval-runs context:"demo" runOnChange:true
--comment: A handful of completed evaluation runs so the dashboard shows recorded scores.

INSERT INTO evaluation_runs (id, suite_id, agent_id, status, total_cases, passed_cases, failed_cases, average_score, average_latency_ms, started_at, completed_at, created_at) VALUES
('demo_eval_run_001', 'demo_eval_suite_factual', 'demo_research_acme', 'COMPLETED', 5, 5, 0, 0.94, 1820, NOW() - INTERVAL '12 days', NOW() - INTERVAL '12 days' + INTERVAL '2 minutes', NOW() - INTERVAL '12 days'),
('demo_eval_run_002', 'demo_eval_suite_factual', 'demo_writer_acme',   'COMPLETED', 5, 4, 1, 0.82, 2140, NOW() - INTERVAL '8 days',  NOW() - INTERVAL '8 days'  + INTERVAL '3 minutes', NOW() - INTERVAL '8 days'),
('demo_eval_run_003', 'demo_eval_suite_tone',    'demo_support_acme',  'COMPLETED', 3, 3, 0, 0.91, 1380, NOW() - INTERVAL '6 days',  NOW() - INTERVAL '6 days'  + INTERVAL '1 minute',  NOW() - INTERVAL '6 days'),
('demo_eval_run_004', 'demo_eval_suite_tone',    'demo_support_globex','COMPLETED', 3, 2, 1, 0.74, 1620, NOW() - INTERVAL '4 days',  NOW() - INTERVAL '4 days'  + INTERVAL '2 minutes', NOW() - INTERVAL '4 days')
ON CONFLICT (id) DO UPDATE SET
    status = EXCLUDED.status,
    passed_cases = EXCLUDED.passed_cases,
    failed_cases = EXCLUDED.failed_cases,
    average_score = EXCLUDED.average_score,
    completed_at = EXCLUDED.completed_at;
