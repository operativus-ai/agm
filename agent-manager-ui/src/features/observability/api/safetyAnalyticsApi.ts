import { ApiClient } from '../../../shared/api/client';

const BASE = '/v1/observability/aggregates';

export interface SafetyHeatmapCell {
  agentId: string;
  day: string; // ISO-8601
  avgScore: number;
  maxScore: number;
  flagged: number;
  total: number;
}

export interface FlaggedRun {
  runId: string;
  agentId: string | null;
  score: number;
  createdAt: string;
}

export interface SafetyAggregateResponse {
  cells: SafetyHeatmapCell[];
  flaggedRunsTopN: FlaggedRun[];
}

export const safetyAnalyticsApi = {
  get: (window = 30) =>
    ApiClient.get<SafetyAggregateResponse>(`${BASE}/safety?window=${window}`),
};
