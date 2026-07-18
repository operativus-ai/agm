// Wire types copied from the AGM contract (verified against agent-manager-ui's
// api modules + the backend controllers). Keep in sync with the platform — or
// replace this file with codegen from /v3/api-docs for drift detection.

// ── Auth ────────────────────────────────────────────────────────────────────

export interface LoginRequest {
  username: string;
  password: string;
}

/** Response of POST /api/auth/login. `token` is the JWT; no org claim — AGM resolves org server-side. */
export interface AuthResponse {
  token: string;
  type: string;
  id: string;
  username: string;
  email: string;
  roles: string[];
}

// ── Agents ──────────────────────────────────────────────────────────────────

/** Subset of AgentConfig (GET /api/agents) the demo needs. */
export interface AgentSummary {
  /** Normalized from the wire's `agentId` (the backend has no `id` key). Use `.id`. */
  id: string;
  /** Raw wire identifier (AgentDefinition.id is @JsonProperty("agentId")). */
  agentId?: string;
  name: string;
  description?: string;
  model?: string;
  active?: boolean;
}

// ── Runs / streaming ────────────────────────────────────────────────────────

export interface RunOptions {
  temperature?: number;
  model?: string;
  systemPrompt?: string;
  maxTokens?: number;
}

/** Body of POST /api/agents/{id}/runs/stream. */
export interface RunRequest {
  message: string;
  stream: boolean;
  sessionId?: string;
  generateFollowups?: boolean;
  options?: RunOptions;
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

/** One `data:` frame of the run stream (SSE over POST). */
export interface StreamChunk {
  /** Legacy content-bearing shape. */
  content?: string;
  type?: 'content' | 'thought' | 'tool_start' | 'tool_end' | 'error' | 'done';
  /** Event-protocol shape (AgentStreamEvent). */
  event?: StreamEvent;
  data?: string;
  timestamp?: number;
}

/**
 * Payload of a PAUSED frame — the HITL required action. TOOL_APPROVAL carries
 * approvalId; SWARM_ESCALATION_APPROVAL carries escalationId. Parsed defensively
 * from StreamChunk.data.
 */
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

// ── HITL approvals ──────────────────────────────────────────────────────────

export type HitlDecision = 'APPROVED' | 'REJECTED';
