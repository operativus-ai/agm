import { ApiClient } from '../../../shared/api/client';

const BASE = '/v1/observability/aggregates';

export interface SessionAnalyticsBucket {
  day: string; // ISO-8601 timestamp (UTC midnight)
  sessionCount: number;
  p50DurationSeconds: number;
  p95DurationSeconds: number;
  avgRunsPerSession: number;
}

export interface SessionAggregateResponse {
  buckets: SessionAnalyticsBucket[];
}

export const sessionsAnalyticsApi = {
  get: (window = 30) => {
    const sp = new URLSearchParams();
    sp.set('window', String(window));
    return ApiClient.get<SessionAggregateResponse>(`${BASE}/sessions?${sp.toString()}`);
  },
};
