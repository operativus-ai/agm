--liquibase formatted sql

--changeset agentmanager:002-seed-data runOnChange:true
--comment: Consolidated reference seed data for Operativus: model catalog, default agents, PII policies, FinOps rates. NO admin user — the first admin is provisioned securely via the opt-in bootstrap (agentmanager.bootstrap.admin.*, see AdminBootstrapRunner). Demo/local logins come from the context:"demo" changesets (demo-admin etc.).

-- ============================================================
-- LLM Models (Current catalog — Gemini-first)
-- ============================================================
INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision, supports_system_instructions, max_context_tokens, max_output_tokens, model_type, created_at, updated_at) VALUES
-- Google (Primary)
('gemini-3.1-pro', 'Gemini 3.1 Pro (Preview)', 'GOOGLE', 'gemini-3.1-pro-preview', TRUE, TRUE, TRUE, 1048576, 65536, 'CHAT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('gemini-3.1-flash', 'Gemini 3.5 Flash', 'GOOGLE', 'gemini-3.5-flash', TRUE, TRUE, TRUE, 1048576, 65536, 'CHAT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('gemini-3.1-flash-lite', 'Gemini 3.1 Flash-Lite', 'GOOGLE', 'gemini-3.1-flash-lite', TRUE, TRUE, TRUE, 1048576, 65536, 'CHAT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('gemini-2.5-pro', 'Gemini 2.5 Pro', 'GOOGLE', 'gemini-2.5-pro', TRUE, TRUE, TRUE, 1048576, 65536, 'CHAT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('gemini-2.5-flash', 'Gemini 2.5 Flash', 'GOOGLE', 'gemini-2.5-flash', TRUE, TRUE, TRUE, 1048576, 8192, 'CHAT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('gemini-2.0-flash', 'Gemini 2.0 Flash (Free)', 'GOOGLE', 'gemini-2.0-flash', TRUE, TRUE, TRUE, 1048576, 8192, 'CHAT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
-- OpenAI
('gpt-5.4', 'GPT-4o', 'OpenAI', 'gpt-4o', TRUE, TRUE, TRUE, 128000, 16384, 'CHAT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('gpt-5.2', 'GPT-4o mini', 'OpenAI', 'gpt-4o-mini', TRUE, TRUE, TRUE, 128000, 16384, 'CHAT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('o3-mini', 'GPT o3-mini', 'OpenAI', 'o3-mini', TRUE, TRUE, TRUE, 200000, 100000, 'CHAT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
-- Anthropic
('claude-4-6-opus', 'Claude 4.6 Opus', 'ANTHROPIC', 'claude-opus-4-6', TRUE, TRUE, TRUE, 200000, 32000, 'CHAT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('claude-4-6-sonnet', 'Claude 4.6 Sonnet', 'ANTHROPIC', 'claude-sonnet-4-6', TRUE, TRUE, TRUE, 200000, 64000, 'CHAT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('claude-4-5-haiku', 'Claude 4.5 Haiku', 'ANTHROPIC', 'claude-haiku-4-5-20251001', TRUE, TRUE, TRUE, 200000, 4096, 'CHAT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('claude-3-5-sonnet-20240620', 'Claude Sonnet 4.5 (snapshot)', 'ANTHROPIC', 'claude-sonnet-4-5-20250929', TRUE, TRUE, TRUE, 200000, 64000, 'CHAT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name,
    model_name = EXCLUDED.model_name,
    max_context_tokens = EXCLUDED.max_context_tokens,
    max_output_tokens = EXCLUDED.max_output_tokens,
    updated_at = CURRENT_TIMESTAMP;

-- ============================================================
-- Default Agents (Gemini-first, Firecrawl tools)
-- ============================================================
-- Tool assignments live in the agents.tools JSONB column (read by AgentEntity.getTools()
-- → AgentDefinition.tools() → AgentClientFactory.resolveTools). They are set inline here.
-- The legacy `agent_tools` join table is NOT mapped by any entity, so seeding it had no
-- effect on the running app — every seeded agent previously booted tool-less. Setting tools
-- at INSERT (not a later UPDATE) means ON CONFLICT DO NOTHING preserves operator edits on re-run.
INSERT INTO agents (id, name, description, model_id, is_team, team_mode, members, active, created_at, updated_at, enforce_json_output, version, instructions, tools) VALUES
('assistant', 'Operativus Assist', 'You are a helpful assistant expert in Operativus OS.', 'gemini-2.5-pro', FALSE, NULL, NULL, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, FALSE, 1, NULL, '["firecrawl_web_search","firecrawl_scrape_url"]'::jsonb),
('document_writer', 'Document Writer', 'You are an expert technical writer and document specialist.', 'gemini-2.5-pro', FALSE, NULL, NULL, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, FALSE, 1, NULL, '["firecrawl_web_search","firecrawl_scrape_url"]'::jsonb),
('investment_team', 'Investment Team', 'A team of investment experts.', 'gemini-2.5-pro', TRUE, 'ROUTER', '["document_writer"]', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, FALSE, 1, NULL, '["firecrawl_web_search","firecrawl_scrape_url"]'::jsonb),
('web_scraper', 'Web Scraper', 'A web scraping agent capable of pulling content from URLs and searching the web.', 'gemini-2.5-pro', FALSE, NULL, NULL, TRUE, NOW(), NOW(), FALSE, 1, 'You are an autonomous Web Scraper, Search, and Documentation Ingestion Agent. You have been provided with specialized tools to interact with the internet. You MUST use the firecrawl_web_search, bulkIngestDocumentationSite, or readWebpage tools to fulfill the user request. You can perform full live web searches using your search tool to answer questions about recent events, news, or general knowledge. DO NOT hallucinate web content. ALWAYS use your tools to fetch real content before answering.', '["readWebpage","pushToKnowledgeBase","bulkIngestDocumentationSite","firecrawl_web_search","firecrawl_scrape_url"]'::jsonb)
ON CONFLICT (id) DO NOTHING;

-- ============================================================
-- Default PII Policies
-- ============================================================
INSERT INTO pii_policies (id, name, description, pattern_type, pattern, scrub_strategy, enabled) VALUES
    (gen_random_uuid(), 'EMAIL_ADDRESS', 'Detects email addresses', 'REGEX', '[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,6}', 'FPE', TRUE),
    (gen_random_uuid(), 'US_SSN', 'Detects US Social Security Numbers (XXX-XX-XXXX)', 'REGEX', '\b\d{3}-\d{2}-\d{4}\b', 'FPE', TRUE),
    (gen_random_uuid(), 'CREDIT_CARD', 'Detects credit card numbers (13-19 digits with optional separators)', 'LUHN', '\b(?:\d[ -]*?){13,19}\b', 'FPE', TRUE),
    (gen_random_uuid(), 'US_PHONE', 'Detects US phone numbers', 'REGEX', '\b(?:\+?1[-.\s]?)?(?:\(?[2-9]\d{2}\)?[-.\s]?)?[2-9]\d{2}[-.\s]?\d{4}\b', 'REDACT', TRUE),
    (gen_random_uuid(), 'IP_ADDRESS', 'Detects IPv4 addresses', 'REGEX', '\b(?:(?:25[0-5]|2[0-4]\d|[01]?\d\d?)\.){3}(?:25[0-5]|2[0-4]\d|[01]?\d\d?)\b', 'REDACT', TRUE)
ON CONFLICT (name) DO NOTHING;

-- ============================================================
-- Seed FinOps Valuation Rates
-- ============================================================
INSERT INTO finops_valuation_rate (model_id, input_rate_per_k_tokens, output_rate_per_k_tokens) VALUES
('gpt-4o', 2.50, 10.00),
('gpt-4o-mini', 0.15, 0.60),
('gpt-4-turbo', 10.00, 30.00),
('claude-3-5-sonnet', 3.00, 15.00),
('claude-3-opus', 15.00, 75.00),
('claude-3-haiku', 0.25, 1.25),
('claude-sonnet-4', 3.00, 15.00),
('gemini-2.5-pro', 1.25, 10.00),
('gemini-2.5-flash', 0.075, 0.30),
('gemini-1.5-pro', 1.25, 5.00),
('llama-3-8b', 0.05, 0.05),
('llama-3-70b', 0.59, 0.79),
('mistral-large', 3.00, 9.00),
('deepseek-r1', 0.55, 2.19)
ON CONFLICT (model_id) DO NOTHING;
