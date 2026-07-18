import { ApiClient } from './client';
import type {
  Team,
  TeamMember,
  TeamTemplate,
  TeamHealth,
  Workflow,
  WorkflowStep,
  WorkflowEdge,
  WorkflowValidationResult,
  WorkflowLayout,
  Approval,
  BulkResolveResponse,
  HumanReviewPending,
  HumanReviewDecideResponse,
  WorkflowExecutionResponse,
  WorkflowResumeResponse,
  WorkflowRouteOptions,
  WorkflowContinueResponse,
  Schedule,
  ScheduleRun,
  TeamManifest,
  SpotBatchJob
} from '../types/orchestration';
import type { TransitionConstraint, PaginatedResponse } from '../types/api';

const BASE_PATH = '/v1';

export const orchestrationApi = {
  // Team Templates (read-only, code-defined in backend)
  getTeamTemplates: () => ApiClient.get<TeamTemplate[]>('/config/team-templates'),

  // Teams
  getTeams: (params?: { page?: number; size?: number; search?: string; showArchived?: boolean }) => {
    const sp = new URLSearchParams();
    sp.append('page', String(params?.page ?? 0));
    sp.append('size', String(params?.size ?? 20));
    if (params?.search) sp.append('search', params.search);
    if (params?.showArchived) sp.append('showArchived', 'true');
    return ApiClient.get<PaginatedResponse<Team>>(`${BASE_PATH}/teams?${sp.toString()}`);
  },
  getTeam: async (id: string) => {
    const [team, members] = await Promise.all([
      ApiClient.get<Team>(`${BASE_PATH}/teams/${id}`),
      ApiClient.get<TeamMember[]>(`${BASE_PATH}/teams/${id}/members`).catch(() => [])
    ]);
    return { ...team, members: members || [] } as Team;
  },
  createTeam: (data: Partial<Team>) => ApiClient.post<Team>(`${BASE_PATH}/teams`, data),
  updateTeam: (id: string, updates: Partial<Team>) =>
    ApiClient.patch<Team>(`${BASE_PATH}/teams/${id}`, updates),
  deleteTeam: (id: string) => ApiClient.delete<void>(`${BASE_PATH}/teams/${id}`),
  cloneTeam: (id: string) => ApiClient.post<Team>(`${BASE_PATH}/teams/${id}/clone`),
  archiveTeam: (id: string) => ApiClient.patch<Team>(`${BASE_PATH}/teams/${id}/archive`),
  restoreTeam: (id: string) => ApiClient.patch<Team>(`${BASE_PATH}/teams/${id}/restore`),
  getTeamHealth: (id: string) => ApiClient.get<TeamHealth>(`${BASE_PATH}/teams/${id}/health`),
  addTeamMember: (teamId: string, member: Partial<TeamMember>) => ApiClient.post<TeamMember>(`${BASE_PATH}/teams/${teamId}/members`, member),
  removeTeamMember: (teamId: string, agentId: string) => ApiClient.delete<void>(`${BASE_PATH}/teams/${teamId}/members/${agentId}`),
  bulkAddMembers: (teamId: string, members: { agentId: string; role: string }[]) =>
    ApiClient.post<TeamMember[]>(`${BASE_PATH}/teams/${teamId}/members/batch`, { members }),

  // FinOps & Manifest
  listTeamManifests: () => ApiClient.get<TeamManifest[]>(`${BASE_PATH}/teams/manifests`),
  updateTeamManifest: (id: string, updates: Partial<TeamManifest>) =>
    ApiClient.patch<TeamManifest>(`${BASE_PATH}/teams/${id}/manifest`, updates),

  // Transition Edges (DAG Constraints)
  getTransitionEdges: (teamId: string) =>
    ApiClient.get<TransitionConstraint[]>(`${BASE_PATH}/teams/${teamId}/edges`),
  addTransitionEdge: (teamId: string, edge: { sourceAgentId: string; targetAgentId: string }) =>
    ApiClient.post<TransitionConstraint>(`${BASE_PATH}/teams/${teamId}/edges`, edge),
  removeTransitionEdge: (teamId: string, edgeId: string) =>
    ApiClient.delete<void>(`${BASE_PATH}/teams/${teamId}/edges/${edgeId}`),

  // Workflows
  getWorkflows: (params?: { page?: number; size?: number }) => {
    const sp = new URLSearchParams();
    sp.append('page', String(params?.page ?? 0));
    sp.append('size', String(params?.size ?? 20));
    return ApiClient.get<PaginatedResponse<Workflow>>(`${BASE_PATH}/workflows?${sp.toString()}`);
  },
  getWorkflow: async (id: string) => {
    const [workflow, steps] = await Promise.all([
      ApiClient.get<Workflow>(`${BASE_PATH}/workflows/${id}`),
      ApiClient.get<WorkflowStep[]>(`${BASE_PATH}/workflows/${id}/steps`).catch(() => [])
    ]);
    return { ...workflow, steps: steps || [] } as Workflow;
  },
  createWorkflow: (data: Partial<Workflow>) => ApiClient.post<Workflow>(`${BASE_PATH}/workflows`, data),
  updateWorkflow: (id: string, updates: Partial<Workflow>) =>
    ApiClient.patch<Workflow>(`/v1/workflows/${id}`, updates),
  deleteWorkflow: (id: string) => ApiClient.delete<void>(`${BASE_PATH}/workflows/${id}`),
  addWorkflowStep: (workflowId: string, step: Partial<WorkflowStep>) => ApiClient.post<WorkflowStep>(`${BASE_PATH}/workflows/${workflowId}/steps`, step),
  // Updates a step's editable config (agent/expression, order, router/condition config). The step's
  // action (node kind) is immutable server-side.
  updateWorkflowStep: (workflowId: string, stepId: string, step: Partial<WorkflowStep>) =>
    ApiClient.patch<WorkflowStep>(`${BASE_PATH}/workflows/${workflowId}/steps/${stepId}`, step),
  removeWorkflowStep: (workflowId: string, stepId: string) => ApiClient.delete<void>(`${BASE_PATH}/workflows/${workflowId}/steps/${stepId}`),
  // DAG edges (REQ-DR-5) — explicit graph topology. Empty list = legacy step_order workflow.
  getWorkflowEdges: (workflowId: string) =>
    ApiClient.get<WorkflowEdge[]>(`${BASE_PATH}/workflows/${workflowId}/edges`),
  // 400 on self-loop / non-member step / duplicate / cycle; 404 if workflow missing or cross-tenant.
  addWorkflowEdge: (workflowId: string, edge: { fromStepId: string; toStepId: string; condition?: string | null }) =>
    ApiClient.post<WorkflowEdge>(`${BASE_PATH}/workflows/${workflowId}/edges`, edge),
  // Relabel an edge's port (condition). 400 on duplicate / cycle (reverted); 404 if missing or cross-tenant.
  updateWorkflowEdge: (workflowId: string, edgeId: string, edge: { condition: string | null }) =>
    ApiClient.patch<WorkflowEdge>(`${BASE_PATH}/workflows/${workflowId}/edges/${edgeId}`, edge),
  removeWorkflowEdge: (workflowId: string, edgeId: string) =>
    ApiClient.delete<void>(`${BASE_PATH}/workflows/${workflowId}/edges/${edgeId}`),
  // Validation overlay: cycle + orphan (unreachable-from-start) step ids. Edge-less workflow = valid.
  validateWorkflowGraph: (workflowId: string) =>
    ApiClient.get<WorkflowValidationResult>(`${BASE_PATH}/workflows/${workflowId}/validate`),
  // Persisted DAG-editor node positions. GET = saved layout (empty if none); PUT = full replace.
  getWorkflowLayout: (workflowId: string) =>
    ApiClient.get<WorkflowLayout>(`${BASE_PATH}/workflows/${workflowId}/layout`),
  saveWorkflowLayout: (workflowId: string, layout: WorkflowLayout) =>
    ApiClient.put<WorkflowLayout>(`${BASE_PATH}/workflows/${workflowId}/layout`, layout),
  cloneWorkflow: (id: string) => ApiClient.post<Workflow>(`${BASE_PATH}/workflows/${id}/clone`),
  runWorkflow: (id: string, input: string, sessionId?: string) =>
    ApiClient.post<WorkflowExecutionResponse>(`${BASE_PATH}/workflows/${id}/run`, { input, sessionId }),
  resumeWorkflowRun: (runId: string, output: string) =>
    ApiClient.post<WorkflowResumeResponse>(`${BASE_PATH}/workflows/runs/${runId}/resume`, { output }),
  // Cancels a non-terminal workflow run. 204 No Content; idempotent no-op on terminal rows.
  cancelWorkflowRun: (runId: string) =>
    ApiClient.delete<void>(`${BASE_PATH}/workflows/runs/${runId}`),
  // Router HITL: choice keys for a run paused at a ROUTER gate, then resume by selecting one.
  getWorkflowRouteOptions: (runId: string) =>
    ApiClient.get<WorkflowRouteOptions>(`${BASE_PATH}/workflows/runs/${runId}/route-options`),
  continueWorkflowRun: (runId: string, choiceKey: string) =>
    ApiClient.post<WorkflowContinueResponse>(`${BASE_PATH}/workflows/runs/${runId}/continue`, { choiceKey }),

  // Approvals
  getApprovals: (params?: { status?: string; page?: number; size?: number }) => {
    const sp = new URLSearchParams();
    sp.append('page', String(params?.page ?? 0));
    sp.append('size', String(params?.size ?? 20));
    // Backend currently only supports pending approvals
    if (params?.status === 'PENDING' || !params?.status) {
      return ApiClient.get<PaginatedResponse<Approval>>(`${BASE_PATH}/approvals/pending?${sp.toString()}`);
    } else {
      // Fallback for resolved items — return empty page envelope
      return Promise.resolve({ content: [], page: { size: 20, number: 0, totalElements: 0, totalPages: 0 } } as PaginatedResponse<Approval>);
    }
  },
  resolveApproval: (id: string, decision: 'APPROVED' | 'REJECTED') =>
    ApiClient.post<Approval>(`${BASE_PATH}/approvals/${id}/resolve`, { decision }),
  bulkResolveApprovals: (ids: string[], decision: 'APPROVED' | 'REJECTED', resolvedBy?: string) =>
    ApiClient.post<BulkResolveResponse>(`${BASE_PATH}/approvals/bulk-resolve`, { ids, decision, resolvedBy }),

  // HumanReview (unified HITL pauses — workflow-step / team-member / agent-tool gates)
  listHumanReviewPending: () =>
    ApiClient.get<HumanReviewPending[]>(`${BASE_PATH}/approvals/human-review`),
  decideHumanReview: (id: string, decision: 'approve' | 'reject', payload?: Record<string, unknown>) =>
    ApiClient.post<HumanReviewDecideResponse>(`${BASE_PATH}/approvals/${id}/decide`, { decision, payload }),

  // Schedules (Updated to /api/v1/schedules)
  getSchedules: (params?: { page?: number; size?: number }) => {
    const sp = new URLSearchParams();
    sp.append('page', String(params?.page ?? 0));
    sp.append('size', String(params?.size ?? 20));
    return ApiClient.get<PaginatedResponse<Schedule>>(`${BASE_PATH}/schedules?${sp.toString()}`);
  },
  getSchedule: async (id: string) => {
    const [schedule, runsPage] = await Promise.all([
      ApiClient.get<Schedule>(`${BASE_PATH}/schedules/${id}`),
      // F4 — /runs is now paginated; extract `.content`. Default size mirrors getScheduleRuns.
      ApiClient.get<PaginatedResponse<ScheduleRun>>(`${BASE_PATH}/schedules/${id}/runs`).catch(() => null)
    ]);
    const runs = runsPage?.content ?? [];
    return { ...schedule, runs } as Schedule;
  },
  createSchedule: (data: Partial<Schedule>) => ApiClient.post<Schedule>(`${BASE_PATH}/schedules`, data),
  updateSchedule: (id: string, data: Partial<Schedule>) => ApiClient.put<Schedule>(`${BASE_PATH}/schedules/${id}`, data),
  deleteSchedule: (id: string) => ApiClient.delete<void>(`${BASE_PATH}/schedules/${id}`),
  triggerSchedule: (id: string) => ApiClient.post<{message: string}>(`${BASE_PATH}/schedules/${id}/trigger`),
  // F4 — returns a paginated `{content, totalElements, ...}` envelope; callers must read `.content`.
  getScheduleRuns: (scheduleId: string, params?: { page?: number; size?: number }) => {
    const sp = new URLSearchParams();
    sp.append('page', String(params?.page ?? 0));
    sp.append('size', String(params?.size ?? 20));
    return ApiClient.get<PaginatedResponse<ScheduleRun>>(
        `${BASE_PATH}/schedules/${scheduleId}/runs?${sp.toString()}`);
  },
  getScheduleBatches: () =>
    ApiClient.get<SpotBatchJob[]>(`${BASE_PATH}/schedules/batches`),
};
