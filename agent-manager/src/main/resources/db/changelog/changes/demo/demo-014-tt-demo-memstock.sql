--liquibase formatted sql

--changeset agentmanager:demo-014-tt-demo-memstock context:"demo" runOnChange:true
--comment: tt-demo-memstock — memoryEnabled=true + stockPrice tool combo. Test bed for Demo #15 (multi-turn memory + tools), exercising the path that previously failed with conversationId=null (Bug #2 / PR #915).

INSERT INTO agents (
    id, name, description, model_id, is_team, active,
    security_tier, compliance_tier,
    approved_for_production, requires_pii_redaction, memory_enabled,
    instructions, primary_owner, support_channel, version,
    org_id, created_at, updated_at
) VALUES (
    'tt-demo-memstock',
    'Memstock (testing demo)',
    'Multi-turn memory + stockPrice tool test agent for Demo #15.',
    'claude-4-5-haiku',
    FALSE, TRUE,
    1, 'TIER_1_STANDARD',
    TRUE, FALSE, TRUE,
    'You are a stock-info assistant. When the user asks about a ticker symbol, call the stockPrice tool. In follow-up turns, reference earlier questions from this conversation when relevant.',
    'sesker@operativus.test',
    '#testing-demo',
    1,
    'DEFAULT_SYSTEM_ORG',
    NOW(),
    NOW()
)
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    model_id = EXCLUDED.model_id,
    memory_enabled = EXCLUDED.memory_enabled,
    instructions = EXCLUDED.instructions,
    org_id = EXCLUDED.org_id,
    updated_at = CURRENT_TIMESTAMP;

-- Tools live in the agents.tools JSONB column (the agent_tools join table is unused by the app).
UPDATE agents SET tools = '["stockPrice"]'::jsonb WHERE id = 'tt-demo-memstock';
