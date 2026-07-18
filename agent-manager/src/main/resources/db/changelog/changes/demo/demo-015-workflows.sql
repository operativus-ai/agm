--liquibase formatted sql

--changeset agentmanager:demo-015-workflows context:"demo" runOnChange:true splitStatements:false
--comment: Three demo workflows with an OFFLINE echo model so they run end-to-end with NO LLM API key. Each step's output is "[<agent name>] <input>", so the threaded payload makes per-step progression directly observable. WF1 exercises a sequential AGENT chain; WF2 a CONDITION gate + HITL ROUTER (requires agm.workflow.router.enabled=true, set in application-demo.properties); WF3 a PARALLEL fan-out + bounded LOOP. The dispatcher walks steps flat by step_order (workflow_edges are NOT consulted on this path), and branch jumps are jump-then-linear — hence the explicit guard CONDITION in WF2 that stops the approved branch from falling through into the rejection step.

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. Offline echo model. provider='ECHO' routes to EchoModelProvider/EchoChatModel,
--    which needs no credential and echoes the caller's last user message.
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision, supports_system_instructions, max_context_tokens, max_output_tokens, model_type, created_at, updated_at) VALUES
('demo_echo_model', 'Echo (offline demo)', 'ECHO', 'echo-demo', FALSE, FALSE, TRUE, 8192, 4096, 'CHAT', NOW(), NOW())
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, provider = EXCLUDED.provider, model_name = EXCLUDED.model_name, updated_at = NOW();

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. Echo-bound demo agents (DEMO_ACME). Distinct names → distinct echo prefixes,
--    so each step is visually attributable in the threaded payload.
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO agents (id, name, description, model_id, is_team, active, security_tier, compliance_tier, approved_for_production, requires_pii_redaction, memory_enabled, instructions, primary_owner, support_channel, version, org_id, created_at, updated_at) VALUES
('demo_echo_research', 'Research (echo)',  'Offline research step for demo workflows.',      'demo_echo_model', FALSE, TRUE, 1, 'TIER_1_STANDARD', TRUE, FALSE, FALSE, 'You are a research analyst.', 'demo-analyst@operativus.test', '#demo-workflows', 1, 'DEMO_ACME', NOW(), NOW()),
('demo_echo_writer',   'Writer (echo)',    'Offline drafting/synthesis step for demo workflows.', 'demo_echo_model', FALSE, TRUE, 1, 'TIER_1_STANDARD', TRUE, FALSE, FALSE, 'You are a technical writer.', 'demo-analyst@operativus.test', '#demo-workflows', 1, 'DEMO_ACME', NOW(), NOW()),
('demo_echo_qa',       'QA Reviewer (echo)','Offline quality-review step for demo workflows.', 'demo_echo_model', FALSE, TRUE, 1, 'TIER_1_STANDARD', TRUE, FALSE, FALSE, 'You evaluate output quality.', 'demo-analyst@operativus.test', '#demo-workflows', 1, 'DEMO_ACME', NOW(), NOW()),
('demo_echo_intake',   'Intake Triage (echo)','Offline intake/triage step for demo workflows.', 'demo_echo_model', FALSE, TRUE, 1, 'TIER_1_STANDARD', TRUE, FALSE, FALSE, 'You triage incoming requests.', 'demo-admin@operativus.test', '#demo-workflows', 1, 'DEMO_ACME', NOW(), NOW()),
('demo_echo_finance',  'Finance Approver (echo)','Offline finance-approval step for demo workflows.', 'demo_echo_model', FALSE, TRUE, 1, 'TIER_1_STANDARD', TRUE, FALSE, FALSE, 'You approve expenses and state the figure in USD.', 'demo-admin@operativus.test', '#demo-workflows', 1, 'DEMO_ACME', NOW(), NOW()),
('demo_echo_legal',    'Legal Reviewer (echo)','Offline legal-review step for demo workflows.', 'demo_echo_model', FALSE, TRUE, 1, 'TIER_1_STANDARD', TRUE, FALSE, FALSE, 'You flag risky clauses.', 'demo-admin@operativus.test', '#demo-workflows', 1, 'DEMO_ACME', NOW(), NOW())
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description, model_id = EXCLUDED.model_id, active = EXCLUDED.active, instructions = EXCLUDED.instructions, org_id = EXCLUDED.org_id, updated_at = NOW();

-- ═════════════════════════════════════════════════════════════════════════════
-- WF1 — Content Pipeline (sequential AGENT chain). Research → Write → QA.
--   Observe: GET /api/v1/workflows/demo_wf_content/runs shows status COMPLETED;
--   each step's echo prefixes the prior output, so the final payload reads
--   "[QA Reviewer (echo)] [Writer (echo)] [Research (echo)] <your input>".
--   Try: POST /api/v1/workflows/demo_wf_content/run {"input":"Draft a launch blog post for the Q3 analytics release"}
-- ═════════════════════════════════════════════════════════════════════════════
INSERT INTO workflows (id, name, description, org_id, created_at, updated_at) VALUES
('demo_wf_content', 'Demo: Content Pipeline', 'Sequential research → write → QA review chain (offline echo).', 'DEMO_ACME', NOW(), NOW())
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description, org_id = EXCLUDED.org_id, updated_at = NOW();

INSERT INTO workflow_steps (id, workflow_id, step_order, agent_id, action, requires_confirmation, created_at, updated_at) VALUES
('demo_wfstep_content_research', 'demo_wf_content', 1, 'demo_echo_research', 'AGENT', FALSE, NOW(), NOW()),
('demo_wfstep_content_write',    'demo_wf_content', 2, 'demo_echo_writer',   'AGENT', FALSE, NOW(), NOW()),
('demo_wfstep_content_qa',       'demo_wf_content', 3, 'demo_echo_qa',       'AGENT', FALSE, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- ═════════════════════════════════════════════════════════════════════════════
-- WF2 — Expense Approval (CONDITION gate + HITL ROUTER). All paths converge on a
--   shared "finalize" AGENT step, which keeps the flow exclusive AND resume-safe:
--   after a HITL pause the engine runs every remaining step as a plain agent, so the
--   post-router steps are all AGENTs (no CONDITION/ROUTER downstream of the pause).
--   step 1 intake (AGENT)
--   step 2 CONDITION contains:over
--           TRUE  (large) → fall through to the ROUTER (step 3)
--           FALSE (small) → ELSE_BRANCH jump straight to finalize (step 5) — auto-approve
--   step 3 ROUTER (HITL) — pauses at AWAITING_ROUTE_SELECTION; operator picks approve|reject
--   step 4 rejection notice (AGENT, writer)   ← reject runs this, then finalize
--   step 5 finalize / record outcome (AGENT, finance)   ← approve & small jump straight here
--   Outcomes: approve → finalize only; reject → rejection notice + finalize; small → finalize.
--   Observe: large requests pause (status AWAITING_ROUTE_SELECTION);
--     GET /api/v1/workflows/runs/{runId}/route-options lists ["approve","reject"];
--     POST /api/v1/workflows/runs/{runId}/continue {"choiceKey":"approve"} resumes it.
--   Try large: {"input":"Expense EXP-2025-0420: $4,250 for the customer offsite — over the $1,000 auto-approve limit."}
--   Try small: {"input":"Expense EXP-2025-0419: $85 taxi to the airport, within policy."}
-- ═════════════════════════════════════════════════════════════════════════════
INSERT INTO workflows (id, name, description, org_id, created_at, updated_at) VALUES
('demo_wf_expense', 'Demo: Expense Approval', 'Amount gate + human approve/reject router (offline echo).', 'DEMO_ACME', NOW(), NOW())
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description, org_id = EXCLUDED.org_id, updated_at = NOW();

-- Non-ROUTER steps. The CONDITION's ELSE target is the finalize step (step 5).
INSERT INTO workflow_steps (id, workflow_id, step_order, agent_id, action, requires_confirmation, on_reject, else_step_id, created_at, updated_at) VALUES
('demo_wfstep_exp_intake',    'demo_wf_expense', 1, 'demo_echo_intake',  'AGENT',     FALSE, NULL,         NULL,                        NOW(), NOW()),
('demo_wfstep_exp_gate',      'demo_wf_expense', 2, 'contains:over',     'CONDITION', FALSE, 'ELSE_BRANCH','demo_wfstep_exp_finalize',  NOW(), NOW()),
('demo_wfstep_exp_rejection', 'demo_wf_expense', 4, 'demo_echo_writer',  'AGENT',     FALSE, NULL,         NULL,                        NOW(), NOW()),
('demo_wfstep_exp_finalize',  'demo_wf_expense', 5, 'demo_echo_finance', 'AGENT',     FALSE, NULL,         NULL,                        NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- ROUTER step (HITL) — choices map choice key → target step id
INSERT INTO workflow_steps (id, workflow_id, step_order, agent_id, action, router_config, requires_confirmation, created_at, updated_at) VALUES
('demo_wfstep_exp_route', 'demo_wf_expense', 3, NULL, 'ROUTER',
 '{"selectorType":"HITL","selectorExpression":null,"choices":{"approve":"demo_wfstep_exp_finalize","reject":"demo_wfstep_exp_rejection"},"defaultChoice":"approve"}'::jsonb,
 FALSE, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- ═════════════════════════════════════════════════════════════════════════════
-- WF3 — Research Fan-out (PARALLEL + LOOP).
--   step_order 1 ×3 PARALLEL — research, finance, legal run concurrently; the engine
--     groups consecutive same-step_order rows and joins their outputs with "\n---\n".
--   step_order 2 AGENT (writer) — synthesizes the three parallel results.
--   step_order 3 LOOP max:2 — repeats the NEXT step (the QA refine) twice.
--   step_order 4 AGENT (qa) — the loop body; runs once per iteration.
--   Observe: WebSocket "LoopIteration" events (1/2, 2/2) and a single COMPLETED run.
--   Try: {"input":"Assess the risk of adopting vendor X for our data pipeline"}
-- ═════════════════════════════════════════════════════════════════════════════
INSERT INTO workflows (id, name, description, org_id, created_at, updated_at) VALUES
('demo_wf_research', 'Demo: Research Fan-out', 'Parallel multi-agent gather → synthesize → bounded refine loop (offline echo).', 'DEMO_ACME', NOW(), NOW())
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description, org_id = EXCLUDED.org_id, updated_at = NOW();

INSERT INTO workflow_steps (id, workflow_id, step_order, agent_id, action, requires_confirmation, created_at, updated_at) VALUES
('demo_wfstep_res_research', 'demo_wf_research', 1, 'demo_echo_research', 'PARALLEL', FALSE, NOW(), NOW()),
('demo_wfstep_res_finance',  'demo_wf_research', 1, 'demo_echo_finance',  'PARALLEL', FALSE, NOW(), NOW()),
('demo_wfstep_res_legal',    'demo_wf_research', 1, 'demo_echo_legal',    'PARALLEL', FALSE, NOW(), NOW()),
('demo_wfstep_res_merge',    'demo_wf_research', 2, 'demo_echo_writer',   'AGENT',    FALSE, NOW(), NOW()),
('demo_wfstep_res_loop',     'demo_wf_research', 3, 'max:2',              'LOOP',     FALSE, NOW(), NOW()),
('demo_wfstep_res_refine',   'demo_wf_research', 4, 'demo_echo_qa',       'AGENT',    FALSE, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;
