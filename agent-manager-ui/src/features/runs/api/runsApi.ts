import { ApiClient } from '../../../shared/api/client';
import type { PaginatedResponse } from '../../../shared/types/api';
import type {
  AgentRunResponse,
  OrchestrationDecision,
  RunCostNode,
  RunReflection,
  RunsQueryParams,
  RunTreeCost,
} from '../types/runs';

const BASE = '/v1/runs';

export const runsApi = {
  list: (params: RunsQueryParams = {}) => {
    const search = new URLSearchParams();
    if (params.sessionId) search.set('sessionId', params.sessionId);
    if (params.agentId) search.set('agentId', params.agentId);
    if (params.status) search.set('status', params.status);
    if (params.page !== undefined) search.set('page', String(params.page));
    if (params.size !== undefined) search.set('size', String(params.size));
    const qs = search.toString();
    return ApiClient.get<PaginatedResponse<AgentRunResponse>>(qs ? `${BASE}?${qs}` : BASE);
  },

  get: (runId: string) =>
    ApiClient.get<AgentRunResponse>(`${BASE}/${encodeURIComponent(runId)}`),

  getOrchestrationDecisions: (runId: string) =>
    ApiClient.get<OrchestrationDecision[]>(
      `${BASE}/${encodeURIComponent(runId)}/orchestration-decisions`,
    ),

  getTreeCost: (runId: string) =>
    ApiClient.get<RunTreeCost>(`${BASE}/${encodeURIComponent(runId)}/tree-cost`),

  getCostTree: (runId: string, maxDepth = 10) =>
    ApiClient.get<RunCostNode>(
      `${BASE}/${encodeURIComponent(runId)}/cost-tree?maxDepth=${maxDepth}`,
    ),

  getReflections: (runId: string) =>
    ApiClient.get<RunReflection[]>(
      `${BASE}/${encodeURIComponent(runId)}/reflections`,
    ),

  // User-side run cancellation. Backend: POST /api/v1/runs/{runId}/cancel
  // (added in BE PR #973). Any authenticated user may cancel a run THEY own;
  // admins may cancel any run within their org. 204 on success or already-
  // terminal (silent no-op); 404 if missing / different tenant / different user.
  // Use this from non-admin contexts (e.g. SessionDetailsPage) — the admin
  // counterpart at AgentAdminApi.cancelRun stays for admins cancelling
  // cross-user runs within their org.
  cancel: (runId: string) =>
    ApiClient.post<void>(`${BASE}/${encodeURIComponent(runId)}/cancel`),
};
