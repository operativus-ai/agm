import { RunStatus } from './enums';

export type NodeRole = 'LEADER' | 'MEMBER';

export interface TeamMember {
  teamId: string;
  agentId: string;
  role: NodeRole;
  joinedAt: string;
}

export interface Team {
  id: string;
  name: string;
  description?: string;
  teamMode?: string;
  leaderId?: string;
  modelId?: string;
  instructions?: string;
  tools?: string[];
  contextWindowSize?: number;
  memoryEnabled?: boolean;
  addHistoryToMessages?: boolean;
  /** §9 MEM-2: when true, orchestrators derive a per-member conversationId so each member's
   *  chat-memory advisor keeps its own bucket. False/undefined = members share the team
   *  session memory (current behaviour). Default false on the server. */
  isolateMemory?: boolean;
  archived?: boolean;
  /** Identifier of the human lead assigned to the team (FinOps + escalation routing). */
  humanLead?: string;
  /** Daily spend ceiling in USD; null/undefined → no ceiling enforced. */
  maxDailySpend?: number;
  /** Minimum required spending authority an actor must hold to invoke this team. */
  minSpendingAuthority?: number;
  // Enriched list fields (populated by paginated list queries)
  memberCount?: number;
  activeMemberCount?: number;
  leaderAgentName?: string;
  createdAt?: string;
  updatedAt: string;
  members: TeamMember[]; // Kept for UI convenience, even if not fully atomic in backend
}

export interface TeamHealth {
  teamId: string;
  memberCount: number;
  activeMemberCount: number;
  inMaintenanceCount: number;
  leaderAgent: { id: string; name: string; active: boolean } | null;
  edgeCount: number;
  currentDailySpend: number;
  maxDailySpend: number;
}

export type WorkflowStepType =
  'AGENT' | 'WEBHOOK' | 'LOOP' | 'CONDITION' | 'PARALLEL' | 'ROUTER' | 'JOIN' | 'FUNCTION' | 'WORKFLOW';

export type RouteSelectorType = 'RULE' | 'LLM' | 'HITL';

/** Config for a ROUTER step (REQ-DR-4): how the outgoing choice key is produced. Persisted as
 *  workflow_steps.router_config; choice keys label the ROUTER's outgoing DAG edges. */
export interface RouterStepConfig {
  selectorType: RouteSelectorType;
  selectorExpression?: string | null; // JSONPath (RULE) / classification prompt (LLM) / null (HITL)
  choices: Record<string, string>;     // choiceKey → target stepId (DAG edges carry the routing)
  defaultChoice?: string | null;
}

export interface WebhookConfig {
  url?: string;
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE';
  headers?: Record<string, string>;
  payloadTemplate?: string;
}

export interface LoopConfig {
  maxIterations?: number;
  endConditionEvaluator?: string;
  innerStepIds?: string[]; // IDs of steps contained within the loop
}

export interface ConditionConfig {
  conditionEvaluator?: string;
  trueBranchStepIds?: string[];
  falseBranchStepIds?: string[];
}

export interface ParallelConfig {
  parallelStepIds?: string[]; // Step IDs to be executed concurrently
}

export interface WorkflowStep {
  id: string;
  workflowId: string;
  stepOrder: number;
  stepType?: WorkflowStepType;
  
  // Agent specific
  agentId?: string;
  action?: string;
  
  // Specific Step Configs
  webhookData?: WebhookConfig;
  loopData?: LoopConfig;
  conditionData?: ConditionConfig;
  parallelData?: ParallelConfig;
  routerConfig?: RouterStepConfig; // ROUTER step (REQ-DR-4) — matches the backend WorkflowStepDTO field

  createdAt: string;
  updatedAt: string;
  
  // UI extended state for live viewers (optional if coming from separate WS feed)
  status?: RunStatus;
  startedAt?: string;
  completedAt?: string;
  output?: string;
}

export interface Workflow {
  id: string;
  name: string;
  description?: string;
  status: RunStatus;
  createdAt: string;
  updatedAt: string;
  /** Populated by the list endpoint (GET /workflows). Single-fetch merges full `steps` instead. */
  stepCount?: number;
  steps: WorkflowStep[];
}

/**
 * An explicit DAG edge between two steps (REQ-DR-5). `condition` is the port label:
 * null = unconditional, "true"/"false" = CONDITION branches, a choice key = ROUTER branch.
 * A workflow with no explicit edges is a legacy flat-list that dispatches by stepOrder.
 */
export interface WorkflowEdge {
  id: string;
  fromStepId: string;
  toStepId: string;
  condition?: string | null;
}

export interface WorkflowGraphResponse {
  steps: WorkflowStep[];
  edges: WorkflowEdge[];
}

/** A saved node position in the DAG editor (REQ-DR-5). Matches the backend NodePosition. */
export interface WorkflowNodePosition {
  stepId: string;
  x: number;
  y: number;
}

/** Persisted DAG-editor layout (REQ-DR-5). Empty positions = no saved layout (ELK fallback). */
export interface WorkflowLayout {
  positions: WorkflowNodePosition[];
}

/** DAG validation overlay report (REQ-DR-5). Matches the backend WorkflowValidationResult. */
export interface WorkflowValidationResult {
  valid: boolean;
  hasCycle: boolean;
  cycleMessage?: string | null;
  unreachableStepIds: string[];
}

export interface Approval {
  id: string;
  runId: string;
  workflowRunId?: string;
  agentId: string;
  toolName: string;
  toolArguments: string;
  status: RunStatus;
  requestedBy: string;
  resolvedBy?: string;
  contextualMessage?: string;
  decisionTier?: string;
  reasoningTrace?: string;
  impactAssessment?: string;
  decisionPackage?: DecisionPackage;
  createdAt: string;
  resolvedAt?: string;
}

export interface DecisionPackage {
  tier: string;
  reasoningTrace: string;
  impactAssessment: string;
  proposedAction: string;
}

export interface BulkResolveResponse {
  resolved: number;
  failed: number;
}

export interface WorkflowExecutionResponse {
  jobId: string;
  workflowId: string;
  sessionId: string;
}

export interface WorkflowResumeResponse {
  jobId: string;
  runId: string;
}

// An undecided HumanReview pause awaiting an operator decision (GET /approvals/human-review).
export interface HumanReviewPending {
  id: string;
  runId: string;
  subjectType: string;
  subjectId: string;
  reason: string;
  options: Record<string, unknown> | null;
  createdAt: string;
  expiresAt: string | null;
}

// Settlement result from POST /approvals/{id}/decide.
export interface HumanReviewDecideResponse {
  pendingId: string;
  runId: string;
  subjectType: string;
  decision: string;
  decidedBy: string;
  decidedAt: string;
}

// Router choices for a run paused at a ROUTER HITL gate (GET /workflows/runs/{id}/route-options).
export interface WorkflowRouteOptions {
  runId: string;
  status: string;
  awaitingRouteSelection: boolean;
  choiceKeys: string[];
  defaultChoice: string | null;
}

// Async-accepted result from POST /workflows/runs/{id}/continue.
export interface WorkflowContinueResponse {
  jobId: string;
  runId: string;
}

export interface ScheduleRun {
  id: string;
  scheduleId: string;
  status: RunStatus;
  startedAt?: string;
  completedAt?: string;
  errorMessage?: string;
  output?: Record<string, any>;
}

export interface Schedule {
  id: string;
  name: string;
  description?: string;
  targetType: 'AGENT' | 'TEAM' | 'WORKFLOW' | string;
  targetId: string;
  cronExpression: string;
  resumeSessionId?: string;
  contextualPrompt?: string;
  isActive: boolean;
  createdAt: string;
  updatedAt: string;
  lastRunAt?: string;
  nextRunAt?: string;
  runs?: ScheduleRun[];
}

// --- Workflow Templates ---

export interface WorkflowTemplateStep {
  stepOrder: number;
  label: string;
  action: string;
  stepType: WorkflowStepType;
}

export interface WorkflowTemplate {
  id: string;
  name: string;
  description: string;
  icon: string;
  steps: WorkflowTemplateStep[];
}

// --- Team Templates ---

export interface TeamTemplateMember {
  role: string;
  label: string;
  description: string;
}

export interface TeamTemplate {
  id: string;
  name: string;
  description: string;
  icon: string;
  teamMode: string | null;
  instructions: string | null;
  memoryEnabled: boolean;
  addHistoryToMessages: boolean;
  contextWindowSize: number | null;
  members: TeamTemplateMember[];
}

/** Extended TeamMember carrying UI-only template slot metadata. */
export interface MemberSlot extends TeamMember {
  slotLabel?: string;
  slotDescription?: string;
  isTemplateSlot?: boolean;
}

export interface AgentRoleDTO {
  agentId: string;
  role: string;
  capabilities: string[];
  requiresPiiRedaction: boolean;
}

export interface TeamManifest {
  teamId: string;
  humanLead: string;
  maxDailySpend: number;
  minSpendingAuthority: number;
  allowedCapabilities: string[];
  agents: Record<string, AgentRoleDTO>;
}

export interface SpotBatchJob {
  id: string;
  job: string;
  status: string;
  progress: number;
  cost: number;
  compute: string;
}
