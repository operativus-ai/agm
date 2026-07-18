--liquibase formatted sql

--changeset agentmanager:demo-003-agents context:"demo" runOnChange:true
--comment: Demo agents — spread across Gemini/OpenAI/Anthropic models and risk tiers, for both orgs.

INSERT INTO agents (id, name, description, model_id, is_team, active, security_tier, compliance_tier, approved_for_production, requires_pii_redaction, memory_enabled, instructions, primary_owner, support_channel, version, org_id, created_at, updated_at) VALUES
-- DEMO_ACME agents
('demo_research_acme', 'Research Assistant (ACME)', 'Deep-research agent for market analysis and competitor scoping.', 'gpt-5.2', FALSE, TRUE, 1, 'TIER_1_STANDARD', TRUE, FALSE, TRUE, 'You are a meticulous research analyst. Cite every claim with a verifiable source. Prefer primary sources.', 'demo-analyst@operativus.test', '#demo-research', 1, 'DEMO_ACME', NOW() - INTERVAL '60 days', NOW() - INTERVAL '2 days'),
('demo_finance_acme',  'Finance Analyst (ACME)',    'FinOps and budgeting agent over internal cost data.',             'claude-4-6-sonnet', FALSE, TRUE, 2, 'TIER_2_STRICT', TRUE, TRUE, TRUE, 'You analyze financial data. Always express figures in USD and call out anomalies of >10%.', 'demo-admin@operativus.test', '#demo-finance', 1, 'DEMO_ACME', NOW() - INTERVAL '55 days', NOW() - INTERVAL '1 day'),
('demo_support_acme',  'Support Agent (ACME)',      'First-line customer support assistant grounded in product docs.', 'gemini-3.1-flash', FALSE, TRUE, 1, 'TIER_1_STANDARD', TRUE, FALSE, FALSE, 'You help customers troubleshoot. Be friendly and concise. Escalate when uncertain.', 'demo-analyst@operativus.test', '#demo-support', 1, 'DEMO_ACME', NOW() - INTERVAL '40 days', NOW() - INTERVAL '3 hours'),
('demo_legal_acme',    'Legal Reviewer (ACME)',     'Document reviewer for contracts and policy. Tier-2 strict (regulated work scope pending product decision on TIER_3_REGULATED).',   'claude-4-6-opus', FALSE, TRUE, 3, 'TIER_2_STRICT', FALSE, TRUE, FALSE, 'You review legal documents. Flag risky clauses. Never give legal advice.', 'demo-admin@operativus.test', '#demo-legal', 1, 'DEMO_ACME', NOW() - INTERVAL '30 days', NOW() - INTERVAL '6 days'),
('demo_writer_acme',   'Document Writer (ACME)',    'Drafts technical documentation and release notes.',               'gpt-5.4', FALSE, TRUE, 1, 'TIER_1_STANDARD', TRUE, FALSE, FALSE, 'You are an expert technical writer. Use precise terminology and active voice.', 'demo-analyst@operativus.test', '#demo-writing', 1, 'DEMO_ACME', NOW() - INTERVAL '28 days', NOW() - INTERVAL '4 hours'),
('demo_router_acme',   'Router Coordinator (ACME)', 'Team coordinator that routes requests to specialist agents.',     'gemini-3.1-pro', TRUE,  TRUE, 1, 'TIER_1_STANDARD', TRUE, FALSE, TRUE, 'You route incoming requests to the best-fit team member based on capability.', 'demo-admin@operativus.test', '#demo-team', 1, 'DEMO_ACME', NOW() - INTERVAL '20 days', NOW() - INTERVAL '1 hour'),
-- DEMO_GLOBEX agents
('demo_support_globex', 'Tier-1 Support (GLOBEX)',  'Customer-facing support agent with KB grounding.',                'gemini-2.5-pro', FALSE, TRUE, 1, 'TIER_1_STANDARD', TRUE, FALSE, FALSE, 'You handle tier-1 customer tickets. Reference runbooks before answering.', 'demo-ops@operativus.test', '#globex-support', 1, 'DEMO_GLOBEX', NOW() - INTERVAL '50 days', NOW() - INTERVAL '12 hours'),
('demo_qa_globex',      'QA Assistant (GLOBEX)',    'Tests outputs of other agents for quality and tone.',             'gpt-5.2', FALSE, TRUE, 1, 'TIER_1_STANDARD', TRUE, FALSE, FALSE, 'You evaluate agent outputs against quality rubrics.', 'demo-ops@operativus.test', '#globex-qa', 1, 'DEMO_GLOBEX', NOW() - INTERVAL '22 days', NOW() - INTERVAL '2 days'),
('demo_planner_globex', 'Project Planner (GLOBEX)', 'Plans multi-step project tasks via planning team.',               'claude-4-6-sonnet', TRUE, TRUE, 1, 'TIER_1_STANDARD', TRUE, FALSE, TRUE, 'Plan multi-step tasks. Decompose the goal into ordered subgoals.', 'demo-ops@operativus.test', '#globex-planning', 1, 'DEMO_GLOBEX', NOW() - INTERVAL '15 days', NOW() - INTERVAL '5 hours')
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    model_id = EXCLUDED.model_id,
    is_team = EXCLUDED.is_team,
    active = EXCLUDED.active,
    security_tier = EXCLUDED.security_tier,
    compliance_tier = EXCLUDED.compliance_tier,
    approved_for_production = EXCLUDED.approved_for_production,
    requires_pii_redaction = EXCLUDED.requires_pii_redaction,
    memory_enabled = EXCLUDED.memory_enabled,
    instructions = EXCLUDED.instructions,
    primary_owner = EXCLUDED.primary_owner,
    support_channel = EXCLUDED.support_channel,
    org_id = EXCLUDED.org_id,
    updated_at = CURRENT_TIMESTAMP;

-- Attach knowledge bases (JSONB array column on agents)
UPDATE agents SET knowledge_base_ids = '["d0000002-0000-0000-0000-000000000001", "d0000002-0000-0000-0000-000000000002"]'::jsonb
WHERE id IN ('demo_research_acme', 'demo_writer_acme');

UPDATE agents SET knowledge_base_ids = '["d0000002-0000-0000-0000-000000000001"]'::jsonb
WHERE id IN ('demo_support_acme', 'demo_finance_acme');

UPDATE agents SET knowledge_base_ids = '["d0000002-0000-0000-0000-000000000003"]'::jsonb
WHERE id IN ('demo_support_globex', 'demo_planner_globex');

-- Tool bindings via the agents.tools JSONB column (read by AgentEntity.getTools()). The legacy
-- agent_tools join table is unused by the app, so seeding it left these demo agents tool-less.
-- Same UPDATE pattern as the knowledge-base attach above.
UPDATE agents SET tools = '["firecrawl_web_search","firecrawl_scrape_url","readWebpage"]'::jsonb
WHERE id = 'demo_research_acme';

UPDATE agents SET tools = '["firecrawl_web_search"]'::jsonb
WHERE id IN ('demo_writer_acme', 'demo_support_acme', 'demo_support_globex');
