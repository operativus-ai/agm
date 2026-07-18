--liquibase formatted sql

--changeset agentmanager:demo-004-teams context:"demo" runOnChange:true
--comment: Demo teams — one Router coordinator in ACME, one Planner coordinator in GLOBEX. Includes member assignments.

INSERT INTO teams (id, name, description, team_mode, leader_id, model_id, instructions, context_window_size, memory_enabled, add_history_to_messages, org_id, created_at, updated_at) VALUES
('demo_team_router_acme', 'ACME Router Team', 'Routes incoming queries to specialists by capability match.', 'ROUTER', 'demo_router_acme', 'gemini-3.1-pro', 'Pick the best specialist for the user request. Prefer experts over generalists.', 8192, TRUE, TRUE, 'DEMO_ACME', NOW() - INTERVAL '20 days', NOW() - INTERVAL '1 hour'),
('demo_team_planner_globex', 'GLOBEX Planning Team', 'Decomposes complex projects into ordered subtasks.', 'PLANNER', 'demo_planner_globex', 'claude-4-6-sonnet', 'Decompose the goal into 3-7 ordered subtasks. Assign each to the right member.', 8192, TRUE, TRUE, 'DEMO_GLOBEX', NOW() - INTERVAL '15 days', NOW() - INTERVAL '5 hours')
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    team_mode = EXCLUDED.team_mode,
    leader_id = EXCLUDED.leader_id,
    model_id = EXCLUDED.model_id,
    instructions = EXCLUDED.instructions,
    org_id = EXCLUDED.org_id,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO team_members (team_id, agent_id, role, joined_at) VALUES
('demo_team_router_acme', 'demo_research_acme', 'SPECIALIST', NOW() - INTERVAL '20 days'),
('demo_team_router_acme', 'demo_finance_acme',  'SPECIALIST', NOW() - INTERVAL '20 days'),
('demo_team_router_acme', 'demo_support_acme',  'SPECIALIST', NOW() - INTERVAL '20 days'),
('demo_team_router_acme', 'demo_writer_acme',   'SPECIALIST', NOW() - INTERVAL '18 days'),
('demo_team_planner_globex', 'demo_support_globex', 'SPECIALIST', NOW() - INTERVAL '15 days'),
('demo_team_planner_globex', 'demo_qa_globex',      'REVIEWER',   NOW() - INTERVAL '15 days')
ON CONFLICT DO NOTHING;
