// AGM wire types — verified against agent-manager-ui's api modules and the
// backend controllers. A production client would codegen these from /v3/api-docs;
// they are hand-mirrored here so the harness stays a zero-build, readable reference.

// ── Auth ────────────────────────────────────────────────────────────────────

export interface LoginRequest {
  username: string;
  password: string;
}

/** POST /api/auth/login. JWT in `token`; no org claim — AGM resolves org server-side. */
export interface AuthResponse {
  token: string;
  type: string;
  id: string;
  username: string;
  email: string;
  roles: string[];
}

// ── Agents ──────────────────────────────────────────────────────────────────

export interface AgentSummary {
  /**
   * The agent identifier used in run/session paths. NOTE: the backend serializes
   * this as `agentId` (AgentDefinition's `id` field is @JsonProperty("agentId")),
   * so the wire JSON has no `id` key — `listAgents()` normalizes `id` from
   * `agentId`. Read `.id` in client code.
   */
  id: string;
  /** Raw wire field (= the identifier). Present on responses; use `id`. */
  agentId?: string;
  name: string;
  description?: string;
  model?: string;
  active?: boolean;
}

// ── Runs / streaming ────────────────────────────────────────────────────────

export interface RunRequest {
  message: string;
  stream: boolean;
  sessionId?: string;
  generateFollowups?: boolean;
  options?: {
    temperature?: number;
    model?: string;
    systemPrompt?: string;
    maxTokens?: number;
  };
}

export type StreamEvent =
  | 'START'
  | 'REASONING_DELTA'
  | 'CONTENT_DELTA'
  | 'CONTENT_DONE'
  | 'TOOL_START'
  | 'TOOL_END'
  | 'TOOL_ERROR'
  | 'PAUSED'
  | 'AGENT_SWITCH'
  | 'METRICS'
  | 'STOP'
  | 'ERROR'
  | 'FOLLOWUP_SUGGESTION';

/** One `data:` frame of the SSE-over-POST run stream. */
export interface StreamChunk {
  content?: string; // legacy content-bearing shape
  type?: 'content' | 'thought' | 'tool_start' | 'tool_end' | 'error' | 'done';
  event?: StreamEvent; // event-protocol shape (AgentStreamEvent)
  data?: string;
  timestamp?: number;
}

/** Payload of a PAUSED frame — the HITL required action. */
export interface RequiredAction {
  type?: string;
  approvalId?: string;
  escalationId?: string;
  toolName?: string;
  message?: string;
  [key: string]: unknown;
}

/** Terminal per-run usage streamed via the METRICS frame just before STOP. */
export interface UsageSummary {
  inputTokens?: number;
  outputTokens?: number;
  totalTokens?: number;
  llmCalls?: number;
  model?: string;
  costUsd?: number;
}

// ── Sessions ────────────────────────────────────────────────────────────────

export interface AgentRun {
  id: string;
  agentId: string;
  sessionId: string;
  userId: string;
  status: string;
  startedAt: string;
  completedAt?: string;
  input?: string;
  output?: string;
}

export interface AgentSession {
  id: string;
  userId: string;
  agentId: string;
  title?: string;
  createdAt: string;
  updatedAt: string;
}

/** Spring Page<T> wire shape (direct serialization mode). */
export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

// ── HITL ────────────────────────────────────────────────────────────────────

export type HitlDecision = 'APPROVED' | 'REJECTED';

// ── Phase 1 domains — permissive shapes (optional fields; parse real responses
//    without throwing). Verified against the backend DTOs 2026-07-10. ──────────

/** Sync run: POST /api/agents/{id}/runs → RunResponse. */
export interface SyncRunResponse {
  runId: string;
  sessionId?: string;
  content?: string;
  status?: string;
  metadata?: Record<string, unknown>;
}

/** Background run: POST /api/agents/{id}/runs/background → AgentRunStatusDTO. */
export interface BackgroundRunStatus {
  runId?: string;
  id?: string;
  status?: string;
}

/** GET /api/tools → ToolItem[]. */
export interface ToolItem {
  id: string;
  label: string;
  desc?: string;
  category?: string;
  categoryLabel?: string;
}

/** GET /api/v1/provider-credentials → ProviderCredentialResponse[]. */
export interface ProviderCredential {
  id: string;
  provider: string;
  label?: string;
}

/** POST /api/v1/knowledge-bases → KnowledgeBase. */
export interface KnowledgeBase {
  id: string;
  name: string;
  description?: string;
}

/** GET /api/knowledge?knowledgeBaseId= → Page<KnowledgeContent>. status: PROCESSING|COMPLETED|FAILED. */
export interface KnowledgeContent {
  id: string;
  name?: string;
  status?: string;
  statusMessage?: string;
  knowledgeBaseId?: string;
}

/** GET /api/knowledge/search?query= → Spring AI Document[]. Score in metadata.distance / score. */
export interface RagDocument {
  id?: string;
  text?: string;
  content?: string;
  formattedContent?: string;
  metadata?: Record<string, unknown>;
  score?: number;
}

/** POST /api/v1/workflows → WorkflowDTO. */
export interface WorkflowSummary {
  id: string;
  name: string;
  description?: string;
  stepCount?: number;
}

/** WorkflowStepDTO — an AGENT step is {stepOrder, agentId}. */
export interface WorkflowStep {
  id?: string;
  workflowId?: string;
  stepOrder?: number;
  agentId?: string;
  action?: string;
}

/** WorkflowEdgeDTO. */
export interface WorkflowEdge {
  id?: string;
  fromStepId: string;
  toStepId: string;
  condition?: string;
}

/** GET /api/v1/workflows/{id}/validate → WorkflowValidationResult (shape read defensively). */
export interface WorkflowValidation {
  valid?: boolean;
  errors?: unknown[];
  [key: string]: unknown;
}

/** POST /api/v1/workflows/{id}/run → WorkflowExecutionResponse (async — poll runs). */
export interface WorkflowExecution {
  jobId: string;
  workflowId: string;
  sessionId?: string;
}

/** GET /api/v1/workflows/{id}/runs → Page<WorkflowRunResponse>. */
export interface WorkflowRunSummary {
  id?: string;
  runId?: string;
  status?: string;
  [key: string]: unknown;
}
