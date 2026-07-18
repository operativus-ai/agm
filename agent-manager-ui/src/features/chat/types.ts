import { MessageRole, RunStatus, RequiredActionType } from '../../shared/types/enums';

export { MessageRole };

export interface ChatMessage {
  id: string;
  runId?: string; // Backend Run ID
  role: MessageRole;
  content: string;
  timestamp: string;
  thoughts?: string; 
  toolCalls?: ToolCall[];
  status?: RunStatus; // For HITL state
  metadata?: Record<string, any>; // Carries escalation payloads (e.g., SWARM_ESCALATION_APPROVAL)
  followUpSuggestions?: string[];
  startTimeMs?: number;
  executionTimeMs?: number;
  /** Terminal per-run usage total streamed just before STOP via the METRICS event — see
   *  AgentStreamManager.buildUsageSummaryEvent. The single authoritative token+cost figure. */
  usage?: UsageSummary;
}

/** Per-run token + cost total carried by the terminal METRICS stream frame. All fields optional:
 *  the backend omits zero/absent values (e.g. costUsd is present only when valuation resolved). */
export interface UsageSummary {
  inputTokens?: number;
  outputTokens?: number;
  reasoningTokens?: number;
  totalTokens?: number;
  llmCalls?: number;
  model?: string;
  costUsd?: number;
}

export interface ToolCall {
  id: string;
  name: string;
  arguments: string;
  status: 'pending' | 'success' | 'error';
  result?: string;
}

export interface Agent {
  id: string;
  name: string;
  description: string;
  model: string;
  isReasoningEnabled: boolean;
}

export interface MediaInput {
  type: string; // e.g., "image/png"
  data: string; // Base64 or URL
}

// DTOs matching Backend
export interface RunOptions {
  temperature?: number;
  model?: string;
  systemPrompt?: string;
  maxTokens?: number;
}

export interface RunRequest {
  message: string;
  stream: boolean;
  sessionId?: string;
  media?: MediaInput[];
  userId?: string;
  orgId?: string;
  generateFollowups?: boolean;
  options?: RunOptions;
}

/**
 * Inline per-run telemetry, populated by AgentService.buildMetrics on the BE side.
 * Counter fields are nullable: null = "not captured by any advisor on this run",
 * non-null = "captured." See BE `core/model/RunMetrics.java` javadoc for the contract.
 */
export interface RunMetrics {
  inputTokens?: number | null;
  outputTokens?: number | null;
  reasoningTokens?: number | null;
  llmCallCount?: number | null;
  errorCount?: number | null;
  durationMs: number;
  model?: string | null;
  errorType?: string | null;
  errorMessage?: string | null;
}

export interface RunResponse {
  runId: string;
  sessionId: string;
  content: string;
  metadata?: Record<string, any>;
  tools?: ToolCall[];
  reasoningSteps?: string[];
  status: RunStatus;
  /** Inline per-run telemetry — gap #19. Populated by AgentService.buildMetrics. */
  metrics?: RunMetrics;
}

export interface StreamChunk {
  content?: string;
  type?: 'content' | 'thought' | 'tool_start' | 'tool_end' | 'error' | 'done';
// "AgentStreamEvent" from Phase 2 and Phase A Updates
  event?: 'START' | 'REASONING_DELTA' | 'CONTENT_DELTA' | 'CONTENT_DONE' | 'TOOL_START' | 'TOOL_END' | 'TOOL_ERROR' | 'PAUSED' | 'AGENT_SWITCH' | 'METRICS' | 'STOP' | 'ERROR' | 'FOLLOWUP_SUGGESTION';
  data?: string;
  timestamp?: number;
}

export interface SwarmEscalationApprovalPayload {
  type: typeof RequiredActionType.SWARM_ESCALATION_APPROVAL;
  sourceAgentId: string;
  targetAgentId: string;
  sourceTier: number;
  targetTier: number;
  escalationId: string;
  traceId?: string;
  reasoningLineage?: string;
  dagContext?: string;
}

export interface ToolApprovalPayload {
  type: typeof RequiredActionType.TOOL_APPROVAL;
  tool: string;
  args: any;
  approvalId: string;
  traceId?: string;
  reasoningLineage?: string;
  dagContext?: string;
}
