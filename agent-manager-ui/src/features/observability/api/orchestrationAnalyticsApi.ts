import { ApiClient } from '../../../shared/api/client';
import type { PaginatedResponse } from '../../../shared/types/api';

const BASE = '/v1/observability/aggregates';

export type Granularity = 'HOUR' | 'DAY' | 'WEEK' | 'MONTH';

export interface StrategyCount {
  strategy: string;
  count: number;
}

export interface TimeBucket {
  bucket: string; // ISO-8601 timestamp
  perStrategy: Record<string, number>;
}

export interface OrchestrationAggregateResponse {
  distribution: StrategyCount[];
  overTime: TimeBucket[];
}

export interface OrchestrationDecisionRow {
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

export const orchestrationAnalyticsApi = {
  get: (window = 30, granularity: Granularity = 'DAY') => {
    const sp = new URLSearchParams();
    sp.set('window', String(window));
    sp.set('granularity', granularity);
    return ApiClient.get<OrchestrationAggregateResponse>(`${BASE}/orchestration?${sp.toString()}`);
  },

  listDecisions: (strategy: string, page = 0, size = 20) => {
    const sp = new URLSearchParams();
    sp.set('strategy', strategy);
    sp.set('page', String(page));
    sp.set('size', String(size));
    return ApiClient.get<PaginatedResponse<OrchestrationDecisionRow>>(
      `${BASE}/orchestration-decisions?${sp.toString()}`,
    );
  },
};
