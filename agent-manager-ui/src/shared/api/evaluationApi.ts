import { ApiClient } from './client';
import type { PaginatedResponse } from '../types/api';
import type {
  EvaluationSuite,
  EvaluationCase,
  EvaluationRun,
  CreateEvaluationSuiteRequest,
  CreateEvaluationCaseRequest
} from '../types/evaluation';

export interface AggregateEvalMetrics {
  totalRuns: number;
  totalCases: number;
  passedCases: number;
  failedCases: number;
  passRate: number;        // already rounded to 1dp by backend
  averageScore: number;    // 2dp
  averageLatencyMs: number; // 1dp
}

export interface EvaluationFeedback {
  runId: string;
  rating: number; // 1..5
  comment?: string;
}

export const evaluationApi = {
  // --- Suites ---
  // G3 — backend now returns a paginated envelope; callers receive only `.content`.
  getSuites: async (params?: { page?: number; size?: number }): Promise<EvaluationSuite[]> => {
    const sp = new URLSearchParams();
    sp.append('page', String(params?.page ?? 0));
    sp.append('size', String(params?.size ?? 20));
    const page = await ApiClient.get<PaginatedResponse<EvaluationSuite>>(
        `/v1/evaluations/suites?${sp.toString()}`);
    return page.content ?? [];
  },

  /** G3 — raw paginated form for FE pages that need totals / page-navigation. */
  getSuitesPage: (params?: { page?: number; size?: number }) => {
    const sp = new URLSearchParams();
    sp.append('page', String(params?.page ?? 0));
    sp.append('size', String(params?.size ?? 20));
    return ApiClient.get<PaginatedResponse<EvaluationSuite>>(
        `/v1/evaluations/suites?${sp.toString()}`);
  },
    
  getSuite: (suiteId: string) => 
    ApiClient.get<EvaluationSuite>(`/v1/evaluations/suites/${suiteId}`),

  createSuite: (data: CreateEvaluationSuiteRequest) => 
    ApiClient.post<EvaluationSuite>('/v1/evaluations/suites', data),

  deleteSuite: (suiteId: string) => 
    ApiClient.delete<void>(`/v1/evaluations/suites/${suiteId}`),

  // --- Cases ---
  // G4 — backend now returns a paginated envelope; callers receive only `.content`.
  getCasesForSuite: async (suiteId: string, params?: { page?: number; size?: number }): Promise<EvaluationCase[]> => {
    const sp = new URLSearchParams();
    sp.append('page', String(params?.page ?? 0));
    sp.append('size', String(params?.size ?? 20));
    const page = await ApiClient.get<PaginatedResponse<EvaluationCase>>(
        `/v1/evaluations/suites/${suiteId}/cases?${sp.toString()}`);
    return page.content ?? [];
  },

  addCaseToSuite: (suiteId: string, data: CreateEvaluationCaseRequest) => 
    ApiClient.post<EvaluationCase>(`/v1/evaluations/suites/${suiteId}/cases`, data),

  deleteCase: (_suiteId: string, caseId: string) => 
    ApiClient.delete<void>(`/v1/evaluations/cases/${caseId}`),

  // --- Runs ---
  // G4 — backend now returns a paginated envelope; callers receive only `.content`.
  getRunsForSuite: async (suiteId: string, params?: { page?: number; size?: number }): Promise<EvaluationRun[]> => {
    const sp = new URLSearchParams();
    sp.append('page', String(params?.page ?? 0));
    sp.append('size', String(params?.size ?? 20));
    const page = await ApiClient.get<PaginatedResponse<EvaluationRun>>(
        `/v1/evaluations/suites/${suiteId}/runs?${sp.toString()}`);
    return page.content ?? [];
  },

  getRun: (runId: string) => 
    ApiClient.get<EvaluationRun>(`/v1/evaluations/runs/${runId}/results`),

  triggerEvaluation: (suiteId: string, agentId: string) =>
    ApiClient.post<{ jobId: string }>(`/v1/evaluations/suites/${suiteId}/run?agentId=${agentId}`, {}),

  // --- Aggregate metrics + feedback (UI plan §U440) ---
  getMetrics: () =>
    ApiClient.get<AggregateEvalMetrics>('/v1/evaluations/metrics'),

  submitFeedback: (feedback: EvaluationFeedback) =>
    ApiClient.post<{ status: string }>('/v1/evaluations/feedback', feedback),
};
