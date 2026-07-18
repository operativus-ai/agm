// Typed AGM endpoint bindings the demo uses. Paths verified against the
// backend controllers (AuthController, AgentsController, SessionController,
// ApprovalsController) via agent-manager-ui's api modules.

import { api, setToken, clearToken } from './client';
import type {
  AgentRun,
  AgentSession,
  AgentSummary,
  AuthResponse,
  HitlDecision,
  LoginRequest,
  Page,
} from '../types';

export const authApi = {
  async login(req: LoginRequest): Promise<AuthResponse> {
    const res = await api.post<AuthResponse>('/auth/login', req);
    setToken(res.token);
    return res;
  },

  /** Server-side logout writes the LOGOUT audit row; best-effort, never blocks local sign-out. */
  logout(): void {
    api.post<void>('/auth/logout').catch(() => {});
    clearToken();
  },
};

export const agentsApi = {
  // Normalize `id` from the wire's `agentId` (the backend serializes
  // AgentDefinition.id as @JsonProperty("agentId"), so there's no `id` key).
  list: async (): Promise<AgentSummary[]> => {
    const raw = await api.get<AgentSummary[]>('/agents?includeInactive=false');
    return raw.map((a) => ({ ...a, id: a.agentId ?? a.id }));
  },
};

export const sessionsApi = {
  list: (agentId: string) =>
    api.get<Page<AgentSession>>(`/sessions?page=0&size=20&agentId=${encodeURIComponent(agentId)}`),
  runs: (sessionId: string) => api.get<AgentRun[]>(`/sessions/${sessionId}/runs`),
};

export const approvalsApi = {
  /** Resolve a TOOL_APPROVAL HITL pause (approvalId comes from the PAUSED stream frame). */
  resolveToolApproval: (approvalId: string, decision: HitlDecision) =>
    api.post<unknown>(`/v1/approvals/${approvalId}/resolve`, { decision }),
  /** Resolve a SWARM_ESCALATION_APPROVAL pause. */
  resolveEscalation: (escalationId: string, decision: HitlDecision) =>
    api.post<unknown>(`/v1/escalations/${escalationId}/resolve`, { decision }),
};
