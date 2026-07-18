/**
 * Shared API DTO Definitions
 * Mapped to Spring Boot Backend Entities
 */
import { RunStatus } from './enums';

// ==========================================
// SPRING DATA PAGE ENVELOPE
// ==========================================

/**
 * Generic wrapper matching Spring Data's {@code Page<T>} JSON serialization
 * under Spring Boot 4 / Spring Data 4 — metadata is nested under {@code page}.
 * Used by all paginated list endpoints across the platform.
 */
export interface PaginatedResponse<T> {
  content: T[];
  page: {
    size: number;
    number: number;
    totalElements: number;
    totalPages: number;
  };
}

// ==========================================
// AGENT REGISTRY & CONFIG
// ==========================================

export interface AgentConfig {
  agentId: string; // Mapped from 'agentId'
  name: string;
  description: string;
  instructions?: string; // Mapped from 'instructions'
  model: string; // Mapped from 'model'
  contextWindowSize?: number;
  memoryEnabled?: boolean;
  addHistoryToMessages?: boolean;
  tools?: string[];
  isReasoningEnabled?: boolean; // Mapped from 'isReasoningEnabled'
  enforceJsonOutput?: boolean; // Mapped from 'enforceJsonOutput'
  isTeam?: boolean;
  teamMode?: string;
  members?: string[];
  allowedRoles?: string[];
  /** §9 MEM-2: only meaningful for team agents. When true, orchestrators derive a
   *  per-member conversationId so each member's chat-memory advisor keeps its own
   *  bucket. When false/null (default), every member shares the team's session memory. */
  isolateMemory?: boolean;
  requiresPiiRedaction?: boolean;
  preHooks?: string[];
  postHooks?: string[];
  approvedForProduction?: boolean;
  maintenanceMode?: boolean;
  active?: boolean;
  configuration?: Record<string, any>;
  markdownDocs?: string;
  supportChannel?: string;
  primaryOwner?: string;
  supportedLocales?: string[];
  accessibilityCompatibility?: string;
  trainingDatasets?: string[];
  knowledgeBaseIds?: string[]; // Mapped from 'knowledgeBaseIds'
  securityTier?: number; // Mapped from 'securityTier' — default: 1
  complianceTier?: 'TIER_1_STANDARD' | 'TIER_2_STRICT';
  compressionThreshold?: number;
  summarizationThreshold?: number;
  optimizationModelId?: string;
  fallbackModelIds?: string[];
  temperature?: number;
  topP?: number;
  frequencyPenalty?: number;
  systemPromptMode?: string;
  maxConcurrentExecutions?: number;
  finOpsTokenBudget?: number;
  finOpsRiskTier?: 'UNRESTRICTED' | 'LOW_RISK' | 'MODERATE_RISK' | 'STRICT' | 'CRITICAL';
  /** FE-only field carrying the template id used to seed the create form
   *  (set by AgentFormModal when the user picks a template). The BE
   *  AgentDefinition record does NOT have this field; Jackson silently
   *  drops it on the create request via @JsonIgnoreProperties. Cleanup
   *  candidate — see docs/analysis/api-sync.md (audit 2026-05-10). */
  agentTemplate?: string;
}

export interface DeveloperMetrics {
  testabilityScore: number;
  maintainabilityGrade: string;
  evaluationCount: number;
}

export interface TopologyNode {
  id: string;
  label: string;
  type: string;
}

export interface TopologyEdge {
  id: string;
  source: string;
  target: string;
}

export interface TransitionConstraint {
  id: string;
  sourceAgentId: string;
  targetAgentId: string;
}

export interface AgentTopology {
  nodes: TopologyNode[];
  edges: TopologyEdge[];
  transitionEdges: TransitionConstraint[];
}

export interface AgentRun {
  id: string;
  agentId: string;
  sessionId: string;
  userId?: string;
  orgId?: string;
  parentRunId?: string;
  input: string;
  output: string;
  status: RunStatus;
  requiredAction?: string;
  createdAt: string;
  updatedAt: string;
  createdBy?: string;
  updatedBy?: string;
}

export interface AgentAudit {
  id: string;
  agentId: string;
  action: string;
  username: string;
  changeset: string;
  createdAt: string;
}

export interface AgentStatus {
  agentId: string;
  status: 'active' | 'inactive' | 'error';
  lastActive?: string; // ISO Date
}

// ==========================================
// KNOWLEDGE BASE (RAG)
// ==========================================

export interface KnowledgeDocument {
  id: string; // UUID
  name: string; // Mapped from 'name'
  description?: string;
  contentType: string; // Mapped from 'content_type'
  uri?: string;
  size: number;
  status: RunStatus; // Matching Backend Enum style
  statusMessage?: string;
  knowledgeBaseId?: string; // UUID linking to a specific KnowledgeBase
  metadata?: Record<string, any>;
  vectorIds?: string[];
  contentHash?: string;
  ownerId?: string | null;
  accessCount?: number;
  createdAt: string; // ISO Date
  updatedAt: string;
}

export interface KnowledgeUploadResponse {
  documentId: string;
  status: string;
  message: string;
}

export interface BulkActionResponse {
  jobId: string;
}

export interface AgentSummary {
  agentId: string;
  name: string;
  description?: string;
}

export interface KnowledgeBase {
  id: string; // UUID
  name: string;
  description?: string;
  ownerId?: string | null;
  orgId?: string | null;
  createdAt: string; // ISO Date
  updatedAt: string;
  documentCount?: number;
}

// ==========================================
// WORKFLOW / TRACE (Optional Phase 2)
// ==========================================

export interface WorkflowState {
  workflowId: string;
  agentId: string;
  status: RunStatus;
  startTime: string;
  endTime?: string;
  steps: number;
}

// ==========================================
// USER ADMIN
// ==========================================

export interface UserAdmin {
  id: string;
  username: string;
  email: string;
  roles: string[];
  disabled: boolean;
  lastLoginAt?: string;
}

export interface UserCreateRequest {
  username: string;
  email: string;
  password: string;
  roles: string[];
}

export interface UserUpdateRequest {
  email?: string;
  roles?: string[];
  disabled?: boolean;
}
