/** Mirrors BE control.dto.RoutingDecisionResponse (DR-FR-4). */
export interface RoutingDecisionResponse {
  id: string;
  orgId: string;
  userId: string | null;
  sessionId: string | null;
  messageHash: string;
  messageLength: number | null;
  resolvedAgentId: string | null;
  resolutionStatus: 'RESOLVED' | 'UNRESOLVED' | 'ERROR' | null;
  strategyUsed: 'DEFAULT_ROUTER' | 'LLM_CLASSIFIER' | 'RULE_SUBSTRING' | 'SEMANTIC_SCORING' | 'FALLBACK' | 'NONE' | null;
  confidence: number | null;
  latencyMs: number | null;
  candidateCount: number | null;
  rationale: string | null;
  traceId: string | null;
  createdAt: string;
}

/** Spring Page<T> wire shape (spring.data.web.pageable.serialization-mode=direct). */
export interface RoutingDecisionsPage {
  content: RoutingDecisionResponse[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}
