/**
 * Mirrors BE control.dto.OrgRoutingConfigResponse (DR-FR-1..3 fields).
 */
export interface OrgRoutingConfigResponse {
  id: string;
  orgId: string;
  defaultRouterAgentId: string | null;
  fallbackAgentId: string | null;
  llmClassifierEnabled: boolean;
  ruleClassifierEnabled: boolean;
  classifierModelId: string | null;
  semanticScoringEnabled: boolean;
  createdAt: string | null;
  updatedAt: string | null;
}

/** Mirrors BE control.dto.RoutingEmbeddingsBackfillResponse. */
export interface RoutingEmbeddingsBackfillResponse {
  orgId: string;
  totalAgents: number;
  embedded: number;
}

/** Mirrors BE control.dto.OrgRoutingConfigRequest. */
export interface OrgRoutingConfigRequest {
  defaultRouterAgentId?: string | null;
  fallbackAgentId?: string | null;
  llmClassifierEnabled?: boolean | null;
  ruleClassifierEnabled?: boolean | null;
  classifierModelId?: string | null;
  semanticScoringEnabled?: boolean | null;
}
