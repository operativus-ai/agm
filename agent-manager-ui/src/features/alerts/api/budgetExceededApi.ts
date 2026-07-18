import { ApiClient } from '../../../shared/api/client';

const BASE = '/observability/budget-exceeded-feed';

export interface BudgetExceededEvent {
  id: number;
  runId: string;
  agentId: string | null;
  payload: Record<string, unknown> | null;
  eventTs: string;
}

export interface BudgetExceededFeedResponse {
  events: BudgetExceededEvent[];
  nextCursor: string;
}

export const budgetExceededApi = {
  getFeed: (sinceIso?: string, limit = 50) => {
    const sp = new URLSearchParams();
    if (sinceIso) sp.set('since', sinceIso);
    sp.set('limit', String(limit));
    return ApiClient.get<BudgetExceededFeedResponse>(`${BASE}?${sp.toString()}`);
  },
};
