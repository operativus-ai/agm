import { ApiClient } from '../../../shared/api/client';
import type { PaginatedResponse } from '../../../shared/types/api';

const BASE = '/v1/observability/background-jobs';

export type JobStatus = 'QUEUED' | 'PROCESSING' | 'PAUSED' | 'COMPLETED' | 'FAILED' | 'DLQ';

export interface BackgroundJob {
  id: string;
  agentId: string | null;
  jobType: string | null;
  status: JobStatus;
  retryCount: number;
  maxRetries: number;
  errorMessage: string | null;
  priority: string | null;
  nextRetryAt: string | null;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
  lockedAt: string | null;
}

export interface BackgroundJobQueryParams {
  status?: JobStatus;
  page?: number;
  size?: number;
}

export const backgroundJobApi = {
  list: (params: BackgroundJobQueryParams = {}) => {
    const sp = new URLSearchParams();
    if (params.status) sp.set('status', params.status);
    sp.set('page', String(params.page ?? 0));
    sp.set('size', String(params.size ?? 25));
    return ApiClient.get<PaginatedResponse<BackgroundJob>>(`${BASE}?${sp.toString()}`);
  },

  retry: (id: string) =>
    ApiClient.post<Record<string, string>>(`${BASE}/${encodeURIComponent(id)}/retry`),

  statusSummary: () =>
    ApiClient.get<Record<JobStatus, number>>(`${BASE}/status-summary`),

  // Administrative queue pause/resume. Paused = QUEUED rows stop being picked up;
  // the flag is persisted to app_settings, so it survives a JVM restart.
  getPauseState: () =>
    ApiClient.get<{ paused: boolean }>(`${BASE}/pause-state`),

  pause: () =>
    ApiClient.post<void>(`${BASE}/pause`),

  resume: () =>
    ApiClient.post<void>(`${BASE}/resume`),
};

export type RetryFailureReason = 'not_found' | 'not_failed' | 'max_retries';

/** Maps an ApiError thrown by retry() to a discriminated reason the UI can branch on. */
export const classifyRetryError = (err: unknown): RetryFailureReason | null => {
  const e = err as { status?: number; body?: { reason?: string } };
  if (!e || typeof e.status !== 'number') return null;
  if (e.status === 404) return 'not_found';
  if (e.status === 409) {
    const reason = e.body?.reason;
    return reason === 'not_failed' ? 'not_failed' : 'not_failed';
  }
  if (e.status === 422) return 'max_retries';
  return null;
};
