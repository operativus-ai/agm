--liquibase formatted sql

--changeset agentmanager:001-schema runOnChange:true
--comment: Consolidated baseline schema for Procurator. All tables with all columns defined inline. No ALTER TABLE migrations. Fully idempotent (IF NOT EXISTS).

-- ============================================================
-- 0. Extensions
-- ============================================================
CREATE EXTENSION IF NOT EXISTS vector;

-- ============================================================
-- 1. LLM Models
-- ============================================================
CREATE TABLE IF NOT EXISTS models (
    id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    provider VARCHAR(255) NOT NULL,
    base_url VARCHAR(255),
    api_key VARCHAR(255),
    model_name VARCHAR(255),
    supports_tools BOOLEAN NOT NULL DEFAULT TRUE,
    supports_vision BOOLEAN NOT NULL DEFAULT FALSE,
    supports_system_instructions BOOLEAN NOT NULL DEFAULT TRUE,
    model_type VARCHAR(50) NOT NULL DEFAULT 'CHAT',
    max_context_tokens INT,
    max_output_tokens INT,
    thinking_budget_tokens INT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 2. Agent Definitions
-- ============================================================
CREATE TABLE IF NOT EXISTS agents (
    id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    model_id VARCHAR(255),
    optimization_model_id VARCHAR(255),
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
    enforce_json_output BOOLEAN DEFAULT FALSE,
    instructions TEXT,
    tools JSONB,
    -- Engine / Behavioral Tuning
    temperature DOUBLE PRECISION,
    top_p DOUBLE PRECISION,
    frequency_penalty DOUBLE PRECISION,
    system_prompt_mode VARCHAR(50),
    -- Capacity & Governance
    context_window_size INTEGER,
    max_concurrent_executions INTEGER,
    fin_ops_token_budget BIGINT,
    security_tier INTEGER NOT NULL DEFAULT 1,
    compliance_tier VARCHAR(50) DEFAULT 'TIER_1_STANDARD',
    -- Memory & History
    memory_enabled BOOLEAN DEFAULT FALSE,
    add_history_to_messages BOOLEAN DEFAULT TRUE,
    -- Optimization Thresholds
    compression_threshold INT,
    summarization_threshold INT,
    -- Extension Hooks (Pre/Post Execution)
    pre_hooks JSONB DEFAULT '[]',
    post_hooks JSONB DEFAULT '[]',
    -- Metadata
    version INT NOT NULL DEFAULT 0,
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 3. Agent Tools (Element Collection)
-- ============================================================
CREATE TABLE IF NOT EXISTS agent_tools (
    agent_id VARCHAR(255) NOT NULL,
    tool_name VARCHAR(255) NOT NULL,
    PRIMARY KEY (agent_id, tool_name),
    FOREIGN KEY (agent_id) REFERENCES agents(id) ON DELETE CASCADE
);

-- ============================================================
-- 4. Session Storage
-- ============================================================
CREATE TABLE IF NOT EXISTS agent_sessions (
    session_id VARCHAR(255) PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    org_id VARCHAR(255) NOT NULL,
    agent_id VARCHAR(255),
    title VARCHAR(255),
    session_state JSONB DEFAULT '{}',
    messages JSONB,
    summary_blob TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_session_user ON agent_sessions(user_id);

-- ============================================================
-- 5. Knowledge Bases
-- ============================================================
CREATE TABLE IF NOT EXISTS knowledge_bases (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    description TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 6. Knowledge Contents
-- ============================================================
CREATE TABLE IF NOT EXISTS knowledge_contents (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    content_type VARCHAR(50),
    uri TEXT,
    content_hash VARCHAR(64),
    size INT,
    status VARCHAR(50) DEFAULT 'PROCESSING',
    status_message TEXT,
    metadata JSONB DEFAULT '{}',
    vector_ids UUID[],
    knowledge_base_id UUID,
    access_count INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_knowledge_hash ON knowledge_contents(content_hash);
CREATE INDEX IF NOT EXISTS idx_knowledge_status ON knowledge_contents(status);

-- ============================================================
-- 7. Semantic Memory (pgvector)
-- ============================================================
CREATE TABLE IF NOT EXISTS agent_memories (
    id UUID PRIMARY KEY,
    content TEXT,
    embedding vector(768),
    user_id VARCHAR(255),
    memory_type VARCHAR(50)
);
CREATE INDEX IF NOT EXISTS idx_agent_memories_embedding ON agent_memories USING HNSW (embedding vector_cosine_ops);

-- ============================================================
-- 8. Workflow State
-- ============================================================
CREATE TABLE IF NOT EXISTS agent_workflow_state (
    run_id UUID PRIMARY KEY,
    session_id VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL,
    pending_tool_call JSONB,
    tool_call_id VARCHAR(255),
    session_state JSONB DEFAULT '{}',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_workflow_session ON agent_workflow_state(session_id);

-- ============================================================
-- 9. Audit Trail
-- ============================================================
CREATE TABLE IF NOT EXISTS agent_audits (
    id VARCHAR(255) PRIMARY KEY,
    agent_id VARCHAR(255) NOT NULL,
    action VARCHAR(50) NOT NULL,
    username VARCHAR(255),
    changeset JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (agent_id) REFERENCES agents(id)
);
CREATE INDEX IF NOT EXISTS idx_audit_agent ON agent_audits(agent_id);

-- ============================================================
-- 10. Evaluation Suites
-- ============================================================
CREATE TABLE IF NOT EXISTS evaluation_suites (
    id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    created_by VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 11. Evaluation Cases
-- ============================================================
CREATE TABLE IF NOT EXISTS evaluation_cases (
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
CREATE INDEX IF NOT EXISTS idx_evaluation_case_suite ON evaluation_cases(suite_id);

-- ============================================================
-- 12. Evaluation Runs
-- ============================================================
CREATE TABLE IF NOT EXISTS evaluation_runs (
    id VARCHAR(255) PRIMARY KEY,
    suite_id VARCHAR(255) NOT NULL,
    agent_id VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL,
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
CREATE INDEX IF NOT EXISTS idx_evaluation_run_agent ON evaluation_runs(agent_id);
CREATE INDEX IF NOT EXISTS idx_evaluation_run_suite ON evaluation_runs(suite_id);

-- ============================================================
-- 13. Evaluation Results
-- ============================================================
CREATE TABLE IF NOT EXISTS evaluation_results (
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
CREATE INDEX IF NOT EXISTS idx_evaluation_result_run ON evaluation_results(run_id);

-- ============================================================
-- 14. Evaluations (Legacy flat table)
-- ============================================================
CREATE TABLE IF NOT EXISTS evaluations (
    id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    agent_id VARCHAR(255) NOT NULL,
    status VARCHAR(255) NOT NULL,
    score DOUBLE PRECISION,
    input TEXT,
    output TEXT,
    expected_output TEXT,
    created_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_evaluations_agent_id ON evaluations(agent_id);

-- ============================================================
-- 15. Application Settings
-- ============================================================
CREATE TABLE IF NOT EXISTS app_settings (
    setting_key VARCHAR(255) PRIMARY KEY,
    setting_value TEXT,
    description TEXT,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 16. Teams
-- ============================================================
CREATE TABLE IF NOT EXISTS teams (
    id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    team_mode VARCHAR(50),
    leader_id VARCHAR(255),
    model_id VARCHAR(255),
    instructions TEXT,
    context_window_size INT,
    memory_enabled BOOLEAN DEFAULT FALSE,
    add_history_to_messages BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 17. Team Members
-- ============================================================
CREATE TABLE IF NOT EXISTS team_members (
    team_id VARCHAR(255) NOT NULL,
    agent_id VARCHAR(255) NOT NULL,
    role VARCHAR(50),
    joined_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (team_id, agent_id),
    FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE,
    FOREIGN KEY (agent_id) REFERENCES agents(id)
);
CREATE INDEX IF NOT EXISTS idx_team_member_agent ON team_members(agent_id);

-- ============================================================
-- 18. Team Tools (Element Collection)
-- ============================================================
CREATE TABLE IF NOT EXISTS team_tools (
    team_id VARCHAR(255) NOT NULL,
    tool_name VARCHAR(255) NOT NULL,
    CONSTRAINT fk_team_tools_team FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_team_tools_team_id ON team_tools(team_id);

-- ============================================================
-- 19. Team Transition Edges (DAG Routing)
-- ============================================================
CREATE TABLE IF NOT EXISTS team_transition_edges (
    id VARCHAR(255) PRIMARY KEY,
    team_id VARCHAR(255) NOT NULL,
    source_agent_id VARCHAR(255) NOT NULL,
    target_agent_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_edge_team FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE,
    CONSTRAINT fk_edge_source FOREIGN KEY (source_agent_id) REFERENCES agents(id) ON DELETE CASCADE,
    CONSTRAINT fk_edge_target FOREIGN KEY (target_agent_id) REFERENCES agents(id) ON DELETE CASCADE,
    CONSTRAINT uq_edge UNIQUE (team_id, source_agent_id, target_agent_id)
);
CREATE INDEX IF NOT EXISTS idx_transition_edge_team ON team_transition_edges(team_id);
CREATE INDEX IF NOT EXISTS idx_transition_edge_source ON team_transition_edges(source_agent_id);

-- ============================================================
-- 20. Workflows
-- ============================================================
CREATE TABLE IF NOT EXISTS workflows (
    id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 21. Workflow Steps
-- ============================================================
CREATE TABLE IF NOT EXISTS workflow_steps (
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
CREATE INDEX IF NOT EXISTS idx_workflow_step_workflow ON workflow_steps(workflow_id);

-- ============================================================
-- 22. Workflow Runs
-- ============================================================
CREATE TABLE IF NOT EXISTS workflow_runs (
    id VARCHAR(255) PRIMARY KEY,
    workflow_id VARCHAR(255) NOT NULL,
    session_id VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL,
    current_step_order INT NOT NULL,
    current_payload TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_workflow_runs_workflow FOREIGN KEY (workflow_id) REFERENCES workflows(id) ON DELETE CASCADE
);

-- ============================================================
-- 23. Approvals
-- ============================================================
CREATE TABLE IF NOT EXISTS approvals (
    id VARCHAR(255) PRIMARY KEY,
    run_id VARCHAR(255),
    session_id VARCHAR(255) NOT NULL,
    agent_id VARCHAR(255) NOT NULL,
    workflow_run_id VARCHAR(255),
    status VARCHAR(50) NOT NULL,
    tool_name VARCHAR(255) NOT NULL,
    tool_arguments JSONB,
    requested_by VARCHAR(255),
    resolved_by VARCHAR(255),
    contextual_message TEXT,
    resolved_at TIMESTAMP,
    decision_tier VARCHAR(30),
    reasoning_trace TEXT,
    impact_assessment TEXT,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (session_id) REFERENCES agent_sessions(session_id),
    FOREIGN KEY (agent_id) REFERENCES agents(id)
);
CREATE INDEX IF NOT EXISTS idx_approval_session ON approvals(session_id);
CREATE INDEX IF NOT EXISTS idx_approval_status ON approvals(status);

-- ============================================================
-- 24. Schedules
-- ============================================================
CREATE TABLE IF NOT EXISTS schedules (
    id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    cron_expression VARCHAR(255) NOT NULL,
    target_type VARCHAR(50) NOT NULL,
    target_id VARCHAR(255) NOT NULL,
    is_active BOOLEAN DEFAULT TRUE,
    contextual_prompt TEXT,
    resume_session_id VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_schedule_target ON schedules(target_type, target_id);

-- ============================================================
-- 25. Schedule Runs
-- ============================================================
CREATE TABLE IF NOT EXISTS schedule_runs (
    id VARCHAR(255) PRIMARY KEY,
    schedule_id VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL,
    started_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP,
    error_message TEXT,
    output JSONB,
    FOREIGN KEY (schedule_id) REFERENCES schedules(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_schedule_run_schedule ON schedule_runs(schedule_id);

-- ============================================================
-- 26. Agent Runs
-- ============================================================
CREATE TABLE IF NOT EXISTS agent_runs (
    id VARCHAR(255) PRIMARY KEY,
    agent_id VARCHAR(255),
    session_id VARCHAR(255),
    user_id VARCHAR(255),
    org_id VARCHAR(255),
    parent_run_id VARCHAR(255),
    input TEXT,
    output TEXT,
    status VARCHAR(50),
    required_action TEXT,
    created_by VARCHAR(255),
    updated_by VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_agent_runs_session ON agent_runs(session_id);

-- ============================================================
-- 27. Agent Messages
-- ============================================================
CREATE TABLE IF NOT EXISTS agent_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id VARCHAR(255) NOT NULL,
    message_type VARCHAR(50) NOT NULL,
    content TEXT,
    metadata JSONB DEFAULT '{}',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_agent_messages_session FOREIGN KEY (session_id) REFERENCES agent_sessions(session_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_agent_messages_session_id ON agent_messages(session_id);
CREATE INDEX IF NOT EXISTS idx_agent_messages_created_at ON agent_messages(created_at);

-- ============================================================
-- 28. Users & Roles
-- ============================================================
CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username VARCHAR(255) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    roles VARCHAR(255),
    first_name VARCHAR(255),
    last_name VARCHAR(255),
    enabled BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS user_roles (
    user_id UUID NOT NULL,
    role VARCHAR(50) NOT NULL,
    PRIMARY KEY (user_id, role),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- ============================================================
-- 29. PII Policies & Audit
-- ============================================================
CREATE TABLE IF NOT EXISTS pii_policies (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL UNIQUE,
    description TEXT,
    pattern_type VARCHAR(50) NOT NULL DEFAULT 'REGEX',
    pattern VARCHAR(1024) NOT NULL,
    scrub_strategy VARCHAR(50) NOT NULL DEFAULT 'REDACT',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    taxonomic_category VARCHAR(50) DEFAULT 'UNCATEGORIZED',
    compliance_framework VARCHAR(50) DEFAULT 'STANDARD',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS agent_pii_policies (
    agent_id VARCHAR(255) NOT NULL,
    policy_id UUID NOT NULL,
    PRIMARY KEY (agent_id, policy_id),
    CONSTRAINT fk_agent_pii_agent FOREIGN KEY (agent_id) REFERENCES agents(id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_pii_policy FOREIGN KEY (policy_id) REFERENCES pii_policies(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_agent_pii_agent ON agent_pii_policies(agent_id);
CREATE INDEX IF NOT EXISTS idx_agent_pii_policy ON agent_pii_policies(policy_id);

CREATE TABLE IF NOT EXISTS pii_audit_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    agent_id VARCHAR(255),
    policy_name VARCHAR(100) NOT NULL,
    scrub_strategy VARCHAR(50) NOT NULL,
    occurrences INT NOT NULL DEFAULT 1,
    session_id VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_pii_audit_agent ON pii_audit_log(agent_id);
CREATE INDEX IF NOT EXISTS idx_pii_audit_created ON pii_audit_log(created_at);

-- ============================================================
-- 30. Agent Reflections & Governance
-- ============================================================
CREATE TABLE IF NOT EXISTS agent_reflections (
    reflection_id UUID PRIMARY KEY,
    run_id VARCHAR(255) NOT NULL,
    session_id VARCHAR(255) NOT NULL,
    agent_id VARCHAR(255) NOT NULL,
    user_id VARCHAR(255),
    step_index INTEGER NOT NULL,
    phase VARCHAR(50) NOT NULL,
    input_summary TEXT,
    output_summary TEXT,
    reasoning TEXT,
    correction_applied BOOLEAN DEFAULT FALSE,
    tool_calls_snapshot JSONB,
    metadata JSONB,
    orchestration_depth INTEGER,
    parent_reflection_id UUID,
    created_at TIMESTAMP WITHOUT TIME ZONE
);

-- ============================================================
-- 31. Agentic Memory Outbox
-- ============================================================
CREATE TABLE IF NOT EXISTS agentic_memory_outbox (
    outbox_id UUID PRIMARY KEY,
    memory_id UUID NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(50) NOT NULL,
    error_message TEXT,
    retry_count INTEGER NOT NULL DEFAULT 0,
    locked_at TIMESTAMP WITHOUT TIME ZONE,
    created_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE
);

-- ============================================================
-- 32. Cultural Knowledge
-- ============================================================
CREATE TABLE IF NOT EXISTS cultural_knowledge (
    knowledge_id UUID PRIMARY KEY,
    knowledge TEXT NOT NULL,
    topics JSONB,
    created_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE
);

-- ============================================================
-- 33. Agentic Memories
-- ============================================================
CREATE TABLE IF NOT EXISTS agentic_memories (
    memory_id UUID PRIMARY KEY,
    memory TEXT NOT NULL,
    topics JSONB,
    input TEXT,
    user_id VARCHAR(255) NOT NULL,
    agent_id VARCHAR(255),
    team_id VARCHAR(255),
    memory_tier VARCHAR(50) NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE,
    updated_at TIMESTAMP WITHOUT TIME ZONE
);

-- ============================================================
-- 34. UI Components
-- ============================================================
CREATE TABLE IF NOT EXISTS ui_components (
    id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    type VARCHAR(255) NOT NULL,
    description TEXT,
    configuration JSONB,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW()
);

-- ============================================================
-- 35. Extensions (MCP Server Registry)
-- ============================================================
CREATE TABLE IF NOT EXISTS extensions (
    id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    type VARCHAR(50) NOT NULL,
    url VARCHAR(2048),
    description TEXT,
    active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 36. Sandbox Capabilities
-- ============================================================
CREATE TABLE IF NOT EXISTS sandbox_capabilities (
    id VARCHAR(255) PRIMARY KEY,
    agent_id VARCHAR(255) NOT NULL,
    thread_id VARCHAR(255),
    active_capabilities TEXT,
    restricted_paths TEXT,
    memory_isolation VARCHAR(255),
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 37. Spot Batch Jobs
-- ============================================================
CREATE TABLE IF NOT EXISTS spot_batch_jobs (
    id VARCHAR(255) PRIMARY KEY,
    job VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL,
    progress INT DEFAULT 0,
    cost DOUBLE PRECISION DEFAULT 0.0,
    compute VARCHAR(100),
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 38. Threat Events
-- ============================================================
CREATE TABLE IF NOT EXISTS threat_events (
    id VARCHAR(255) PRIMARY KEY,
    timestamp VARCHAR(255),
    agent_id VARCHAR(255),
    threat_level VARCHAR(50),
    type VARCHAR(100),
    target VARCHAR(255),
    status VARCHAR(50),
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 39. Trace Spans
-- ============================================================
CREATE TABLE IF NOT EXISTS trace_spans (
    id VARCHAR(255) PRIMARY KEY,
    parent_id VARCHAR(255),
    name VARCHAR(255) NOT NULL,
    start_time VARCHAR(255),
    end_time VARCHAR(255),
    duration_ms BIGINT DEFAULT 0,
    attributes TEXT,
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 40. FinOps Valuation Rates
-- ============================================================
CREATE TABLE IF NOT EXISTS finops_valuation_rate (
    model_id VARCHAR(255) PRIMARY KEY,
    input_rate_per_k_tokens DOUBLE PRECISION NOT NULL,
    output_rate_per_k_tokens DOUBLE PRECISION NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Finished 001-schema.sql
