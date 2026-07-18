import { ApiClient } from '../../../shared/api/client';
import type { PaginatedResponse } from '../../../shared/types/api';

const BASE = '/v1/workflows';

export type WorkflowRunStatus =
  | 'PENDING'
  | 'QUEUED'
  | 'RUNNING'
  | 'COMPLETED'
  | 'FAILED'
  | 'APPROVED'
  | 'REJECTED'
  | 'EXPIRED'
  | 'PAUSED'
  | 'PROCESSING'
  | 'CREATED'
  | 'CANCELLED'
  | 'AWAITING_ROUTE_SELECTION'
  | 'AWAITING_HUMAN_REVIEW';

export interface WorkflowRun {
  id: string;
  workflowId: string;
  sessionId: string | null;
  status: WorkflowRunStatus;
  lastStepOrder: number | null;
  durationMs: number | null;
  createdAt: string;
  updatedAt: string;
}

/**
 * One node execution in a DAG workflow run (REQ-DR-5) — the per-node trace the frontier
 * scheduler produced. Empty for runs dispatched by the flat step_order engine.
 */
export interface WorkflowNodeRun {
  id: string;
  nodeId: string;
  nodeName: string | null;
  kind: string;
  success: boolean;
  error: string | null;
  paused: boolean;
  pauseKind: string | null;
  content: string | null;
  tokenCost: number | null;
  modelId: string | null;
  startedAt: string | null;
  endedAt: string | null;
}

/**
 * One nested sub-workflow invocation's node trace within a parent run (DAG-6). Children execute
 * under derived run ids, so they never appear in the plain node-runs trace; each group hangs
 * under the parent run's WORKFLOW node named by parentNodeId.
 */
export interface WorkflowChildNodeRuns {
  parentNodeId: string;
  childRunId: string;
  childWorkflowId: string;
  nodeRuns: WorkflowNodeRun[];
}

export const workflowRunsApi = {
  list: (workflowId: string, page = 0, size = 20) => {
    const sp = new URLSearchParams();
    sp.set('page', String(page));
    sp.set('size', String(size));
    return ApiClient.get<PaginatedResponse<WorkflowRun>>(
      `${BASE}/${encodeURIComponent(workflowId)}/runs?${sp.toString()}`,
    );
  },

  // Per-node DAG execution trace for a run. Oldest first; empty for flat-engine runs.
  nodeRuns: (runId: string) =>
    ApiClient.get<WorkflowNodeRun[]>(`${BASE}/runs/${encodeURIComponent(runId)}/node-runs`),

  // Nested sub-workflow traces (what ran INSIDE the run's WORKFLOW nodes), grouped per invocation.
  childNodeRuns: (runId: string) =>
    ApiClient.get<WorkflowChildNodeRuns[]>(`${BASE}/runs/${encodeURIComponent(runId)}/child-node-runs`),
};
