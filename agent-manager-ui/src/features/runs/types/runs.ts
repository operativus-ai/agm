export type RunStatus =
  | 'PENDING'
  | 'QUEUED'
  | 'RUNNING'
  | 'COMPLETED'
  | 'FAILED'
  | 'APPROVED'
  | 'REJECTED'
  | 'EXPIRED'
  | 'PAUSED'
  | 'PROCESSING'
  | 'CREATED'
  | 'CANCELLED';

export const RUN_STATUSES: RunStatus[] = [
  'PENDING',
  'QUEUED',
  'RUNNING',
  'COMPLETED',
  'FAILED',
  'APPROVED',
  'REJECTED',
  'EXPIRED',
  'PAUSED',
  'PROCESSING',
  'CREATED',
  'CANCELLED',
];

export const TERMINAL_RUN_STATUSES: ReadonlySet<RunStatus> = new Set([
  'COMPLETED',
  'FAILED',
  'CANCELLED',
  'REJECTED',
  'EXPIRED',
]);

export interface AgentRunResponse {
  id: string;
  agentId: string;
  sessionId: string | null;
  userId: string | null;
  orgId: string | null;
  parentRunId: string | null;
  status: RunStatus;
  model: string | null;
  inputTokens: number | null;
  outputTokens: number | null;
  durationMs: number | null;
  totalCostUsd: string | number | null;
  errorType: string | null;
  safetyRiskScore: string | number | null;
  orchestrationStrategy: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface RunsQueryParams {
  sessionId?: string;
  agentId?: string;
  status?: RunStatus;
  page?: number;
  size?: number;
}

export interface OrchestrationDecision {
  id: number;
  runId: string;
  orgId: string | null;
  strategy: string | null;
  decisionType: string | null;
  selectedAgentId: string | null;
  rationale: string | null;
  decisionPayload: Record<string, unknown> | null;
  createdAt: string;
}

export interface RunTreeCost {
  rootRunId: string;
  treeTotalCostUsd: string | number | null;
  runCount: number;
}

export interface RunCostNode {
  id: string;
  agentId: string | null;
  depth: number | null;
  inputTokens: number | null;
  outputTokens: number | null;
  totalCostUsd: string | number | null;
  subRuns: RunCostNode[];
}

/** Wire shape of a single reflection trace node served by
 *  `GET /v1/runs/{runId}/reflections`. Mirrors the backend
 *  `AgentReflectionResponse` record. */
export interface RunReflection {
  id: string;
  agentId: string | null;
  content: string | null;
  sourceRunId: string | null;
  createdAt: string;
}

export type AgentRunEventType =
  | 'RUN_START'
  | 'RUN_COMPLETE'
  | 'RUN_FAILED'
  | 'BUDGET_EXCEEDED'
  | 'TOOL_INVOKED'
  | 'TOOL_COMPLETED'
  | 'DELEGATION_START'
  | 'DELEGATION_COMPLETE'
  | 'HANDOFF'
  | 'ORCHESTRATOR_DECISION'
  | 'LLM_REQUEST'
  | 'LLM_RESPONSE';

export const TERMINAL_RUN_EVENT_TYPES: ReadonlySet<AgentRunEventType> = new Set([
  'RUN_COMPLETE',
  'RUN_FAILED',
]);

export interface RunEvent {
  id: number;
  eventType: AgentRunEventType;
  runId: string;
  agentId: string | null;
  parentRunId: string | null;
  sessionId: string | null;
  orgId: string | null;
  orchestrationDepth: number | null;
  payload: Record<string, unknown> | null;
  eventTs: string;
}
