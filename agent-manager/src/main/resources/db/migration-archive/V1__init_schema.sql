-- Enable pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;


-- 0. Models (models)
CREATE TABLE models (
    id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    provider VARCHAR(50) NOT NULL,
    base_url VARCHAR(255),
    api_key VARCHAR(255),
    model_name VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 1. Agent Definitions (agents)
CREATE TABLE agents (
    id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    model_id VARCHAR(255),
    tools JSONB,
    is_reasoning_enabled BOOLEAN DEFAULT FALSE,
    is_team BOOLEAN DEFAULT FALSE,
    team_mode VARCHAR(50),
    members JSONB,
    allowed_roles JSONB,
    requires_pii_redaction BOOLEAN DEFAULT FALSE,
    approved_for_production BOOLEAN DEFAULT FALSE,
    maintenance_mode BOOLEAN DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    knowledge_base_ids JSONB,
    configuration JSONB,
    markdown_docs TEXT,
    support_channel VARCHAR(255),
    primary_owner VARCHAR(255),
    supported_locales JSONB,
    accessibility_compatibility VARCHAR(50),
    training_datasets JSONB,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 2. Session Storage (agent_sessions)
CREATE TABLE agent_sessions (
    session_id VARCHAR(255) PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    org_id VARCHAR(255) NOT NULL,
    agent_id VARCHAR(255),
    title VARCHAR(255),
    session_state JSONB DEFAULT '{}',
    messages JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_session_user ON agent_sessions(user_id);

-- 2a. Knowledge Bases (knowledge_bases)
CREATE TABLE knowledge_bases (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    description TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 2b. Knowledge Contents (knowledge_contents)
CREATE TABLE knowledge_contents (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    content_type VARCHAR(50),      -- 'PDF', 'URL', 'TEXT', 'JSON'
    uri TEXT,                      -- Source location
    content_hash VARCHAR(64),      -- SHA256 for deduplication
    size INT,                      -- File size in bytes
    status VARCHAR(50) DEFAULT 'PROCESSING', -- 'PROCESSING', 'COMPLETED', 'FAILED'
    status_message TEXT,
    metadata JSONB DEFAULT '{}',
    vector_ids UUID[],             -- Array of linked IDs for cascading deletes
    knowledge_base_id UUID,        -- Optional Category association
    access_count INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_knowledge_hash ON knowledge_contents(content_hash);
CREATE INDEX idx_knowledge_status ON knowledge_contents(status);

-- 3. Semantic Memory (agent_memories)
CREATE TABLE agent_memories (
    id UUID PRIMARY KEY,
    content TEXT,
    embedding vector(768),        -- Dimension matches standard Gemini embedding
    user_id VARCHAR(255),          -- Critical for isolation
    memory_type VARCHAR(50)        -- 'USER_PREFERENCE' or 'DOCUMENT_CHUNK'
);
-- HNSW Index for fast similarity search
CREATE INDEX ON agent_memories USING HNSW (embedding vector_cosine_ops);

-- 4. Workflow State (agent_workflow_state)
CREATE TABLE agent_workflow_state (
    run_id UUID PRIMARY KEY,
    session_id VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL, -- 'RUNNING', 'AWAITING_APPROVAL', 'COMPLETED', 'FAILED'
    pending_tool_call JSONB,     -- The frozen tool call waiting for approval
    tool_call_id VARCHAR(255),
    session_state JSONB DEFAULT '{}',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_workflow_session ON agent_workflow_state(session_id);

-- 5. Audit Trail (agent_audits)
CREATE TABLE agent_audits (
    id VARCHAR(255) PRIMARY KEY,
    agent_id VARCHAR(255) NOT NULL,
    action VARCHAR(50) NOT NULL,
    username VARCHAR(255),
    changeset JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (agent_id) REFERENCES agents(id)
);
CREATE INDEX idx_audit_agent ON agent_audits(agent_id);

-- 6. Evaluation Suites (evaluation_suites)
CREATE TABLE evaluation_suites (
    id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    created_by VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 7. Evaluation Cases (evaluation_cases)
CREATE TABLE evaluation_cases (
    id VARCHAR(255) PRIMARY KEY,
    suite_id VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    input TEXT NOT NULL,
    expected_output TEXT,
    system_prompt_override TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (suite_id) REFERENCES evaluation_suites(id) ON DELETE CASCADE
);
CREATE INDEX idx_evaluation_case_suite ON evaluation_cases(suite_id);

-- 8. Evaluation Runs (evaluation_runs)
CREATE TABLE evaluation_runs (
    id VARCHAR(255) PRIMARY KEY,
    suite_id VARCHAR(255) NOT NULL,
    agent_id VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL, -- PENDING, IN_PROGRESS, COMPLETED, FAILED
    total_cases INT DEFAULT 0,
    passed_cases INT DEFAULT 0,
    failed_cases INT DEFAULT 0,
    average_score DOUBLE PRECISION,
    average_latency_ms BIGINT,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    error_message TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (suite_id) REFERENCES evaluation_suites(id),
    FOREIGN KEY (agent_id) REFERENCES agents(id)
);
CREATE INDEX idx_evaluation_run_agent ON evaluation_runs(agent_id);
CREATE INDEX idx_evaluation_run_suite ON evaluation_runs(suite_id);

-- 9. Evaluation Results (evaluation_results)
CREATE TABLE evaluation_results (
    id VARCHAR(255) PRIMARY KEY,
    run_id VARCHAR(255) NOT NULL,
    case_id VARCHAR(255) NOT NULL,
    actual_output TEXT,
    score DOUBLE PRECISION,
    is_passing BOOLEAN,
    latency_ms BIGINT,
    token_usage_total INT,
    error_message TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (run_id) REFERENCES evaluation_runs(id) ON DELETE CASCADE,
    FOREIGN KEY (case_id) REFERENCES evaluation_cases(id)
);
CREATE INDEX idx_evaluation_result_run ON evaluation_results(run_id);

-- 10. Application Settings (app_settings)
CREATE TABLE app_settings (
    setting_key VARCHAR(255) PRIMARY KEY,
    setting_value TEXT,
    description TEXT,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 11. Teams (teams)
CREATE TABLE teams (
    id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    team_mode VARCHAR(50),
    leader_id VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 12. Team Members (team_members)
CREATE TABLE team_members (
    team_id VARCHAR(255) NOT NULL,
    agent_id VARCHAR(255) NOT NULL,
    role VARCHAR(50),
    joined_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (team_id, agent_id),
    FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE,
    FOREIGN KEY (agent_id) REFERENCES agents(id)
);
CREATE INDEX idx_team_member_agent ON team_members(agent_id);

-- 13. Workflows (workflows)
CREATE TABLE workflows (
    id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 14. Workflow Steps (workflow_steps)
CREATE TABLE workflow_steps (
    id VARCHAR(255) PRIMARY KEY,
    workflow_id VARCHAR(255) NOT NULL,
    step_order INT NOT NULL,
    agent_id VARCHAR(255),
    action TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (workflow_id) REFERENCES workflows(id) ON DELETE CASCADE,
    FOREIGN KEY (agent_id) REFERENCES agents(id)
);
CREATE INDEX idx_workflow_step_workflow ON workflow_steps(workflow_id);

-- 15. Approvals (approvals)
CREATE TABLE approvals (
    id VARCHAR(255) PRIMARY KEY,
    run_id VARCHAR(255),
    session_id VARCHAR(255) NOT NULL,
    agent_id VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL, -- PENDING, APPROVED, REJECTED, DISMISSED
    tool_name VARCHAR(255) NOT NULL,
    tool_arguments JSONB,
    requested_by VARCHAR(255),
    resolved_by VARCHAR(255),
    contextual_message TEXT,
    resolved_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (session_id) REFERENCES agent_sessions(session_id),
    FOREIGN KEY (agent_id) REFERENCES agents(id)
);
CREATE INDEX idx_approval_session ON approvals(session_id);
CREATE INDEX idx_approval_status ON approvals(status);

-- 16. Schedules (schedules)
CREATE TABLE schedules (
    id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    cron_expression VARCHAR(255) NOT NULL,
    target_type VARCHAR(50) NOT NULL, -- AGENT, TEAM, WORKFLOW
    target_id VARCHAR(255) NOT NULL,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_schedule_target ON schedules(target_type, target_id);

-- 17. Schedule Runs (schedule_runs)
CREATE TABLE schedule_runs (
    id VARCHAR(255) PRIMARY KEY,
    schedule_id VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL, -- IN_PROGRESS, COMPLETED, FAILED
    started_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP,
    error_message TEXT,
    output JSONB,
    FOREIGN KEY (schedule_id) REFERENCES schedules(id) ON DELETE CASCADE
);
CREATE INDEX idx_schedule_run_schedule ON schedule_runs(schedule_id);

-- 18. Agent Runs (agent_runs)
CREATE TABLE agent_runs (
    id VARCHAR(255) PRIMARY KEY,
    agent_id VARCHAR(255),
    session_id VARCHAR(255),
    user_id VARCHAR(255),
    org_id VARCHAR(255),
    input TEXT,
    output TEXT,
    status VARCHAR(50),
    required_action TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_agent_runs_session ON agent_runs(session_id);

-- 19. Initial Seed Data (Agents)
INSERT INTO agents (id, name, description, model_id, tools, is_team, team_mode, members, active) VALUES
('procurator_assistant', 'Operativus Assist', 'You are a helpful assistant expert in Operativus OS.', 'gemini-2.5-pro', '[]', FALSE, NULL, NULL, TRUE),
('finance_agent', 'Finance Agent', 'You are a finance expert.', 'gemini-2.5-pro', '[]', FALSE, NULL, NULL, TRUE),
('investment_team', 'Investment Team', 'A team of investment experts.', 'gemini-2.5-pro', '[]', TRUE, 'ROUTER', '["finance_agent"]', TRUE),
('web_scraper', 'Web Scraper', 'You are an expert web scraper. You extract information from URLs.', 'gemini-2.5-pro', '["webScraperTool"]', FALSE, NULL, NULL, TRUE)
ON CONFLICT (id) DO NOTHING;
