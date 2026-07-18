import { ApiClient } from '../../../shared/api/client';

/**
 * Wire-format response from POST /api/v1/admin/agents/{id}/quarantine
 * and /unquarantine. Mirrors BE `QuarantineResponse` record.
 *
 * `alreadyQuarantined=true` is the idempotent no-op signal — the action
 * was a hit on an already-set state. UI should treat that as success
 * (no error toast); operator intentionally re-confirmed an existing
 * quarantine.
 */
export interface QuarantineResponse {
  agentId: string;
  runsCancelled: number;
  credentialsLocked: number;
  quarantinedAt: string;
  alreadyQuarantined: boolean;
}

/**
 * Wire-format response from POST /api/v1/admin/incident/halt-all-runs.
 * Mirrors BE `HaltAllRunsResponse` record. The operator UI uses these
 * counters to render the post-incident confirmation banner
 * (e.g. "halted 17 runs across 4 tenants at 12:34 UTC").
 */
export interface HaltAllRunsResponse {
  runsCancelled: number;
  tenantsAffected: number;
  haltedAt: string;
}

const BASE = '/v1/admin';

export const incidentResponseApi = {
  quarantine(agentId: string, reason: string): Promise<QuarantineResponse> {
    return ApiClient.post<QuarantineResponse>(`${BASE}/agents/${agentId}/quarantine`, { reason });
  },

  unquarantine(agentId: string, reason: string): Promise<QuarantineResponse> {
    return ApiClient.post<QuarantineResponse>(`${BASE}/agents/${agentId}/unquarantine`, { reason });
  },

  haltAllRuns(reason: string): Promise<HaltAllRunsResponse> {
    return ApiClient.post<HaltAllRunsResponse>(`${BASE}/incident/halt-all-runs`, { reason });
  },
};
