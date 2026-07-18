import { ApiClient } from '../../../shared/api/client';

const BASE = '/v1/observability/aggregates';

export type Granularity = 'HOUR' | 'DAY' | 'WEEK' | 'MONTH';

export interface ToolStat {
  toolName: string;
  totalCount: number;
  errorCount: number;
  avgDurationMs: number;
}

export interface ToolTimeBucket {
  bucket: string;
  perTool: Record<string, number>;
}

export interface ToolUsageAggregateResponse {
  tools: ToolStat[];
  overTime: ToolTimeBucket[];
}

export const toolAnalyticsApi = {
  get: (window = 30, granularity: Granularity = 'DAY') => {
    const sp = new URLSearchParams();
    sp.set('window', String(window));
    sp.set('granularity', granularity);
    return ApiClient.get<ToolUsageAggregateResponse>(`${BASE}/tools?${sp.toString()}`);
  },
};
