import { ApiClient } from '../../../shared/api/client';

export interface EvaluationMetric {
    target: string;
    score: number;
    metric: string;
    drift: string;
}

export interface TraceSpan {
    id: string;
    parentId: string | null;
    name: string;
    startTime: string; // ISO String
    endTime: string; // ISO String
    durationMs: number;
    attributes: Record<string, string>;
    children?: TraceSpan[];
}

export interface ThreadInfo {
    virtual: boolean;
    name: string;
    daemon: boolean;
}

export const observabilityApi = {
    // Maps to EvaluationController
    getEvaluations: () => ApiClient.get<EvaluationMetric[]>('/v1/evaluations/metrics'),
    submitAgentFeedback: (feedback: any) => ApiClient.post<any>('/v1/evaluations/feedback', feedback),

    // Maps to Observability backend (OpenTelemetry)
    getRunTraces: (runId: string) => ApiClient.get<TraceSpan[]>(`/v1/observability/traces/${runId}`),

    // Maps to DiagnosticsController
    getThreadInfo: () => ApiClient.get<ThreadInfo>('/diagnostics/thread'),
};
