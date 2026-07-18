import { ApiClient } from '../../../shared/api/client';
import type { PaginatedResponse } from '../../../shared/types/api';

const BASE = '/v1/skills';

// A reusable Skill: a named system-prompt snippet + an allowed-tool allowlist that
// can be attached to agents. Backend is ADMIN-only and behind agm.skills.enabled.
export interface Skill {
  id: string;
  orgId: string;
  name: string;
  description: string | null;
  systemPromptSnippet: string | null;
  allowedTools: string[];
  active: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface SkillWriteRequest {
  name: string;
  description?: string;
  systemPromptSnippet?: string;
  allowedTools?: string[];
  active?: boolean;
}

// One agent↔skill attachment (GET /skills/{id}/agents). priority ASC applies first.
export interface SkillAgentBinding {
  agentId: string;
  priority: number;
}

export const skillsApi = {
  list: (page = 0, size = 200) => {
    const sp = new URLSearchParams();
    sp.set('page', String(page));
    sp.set('size', String(size));
    return ApiClient.get<PaginatedResponse<Skill>>(`${BASE}?${sp.toString()}`);
  },
  get: (id: string) => ApiClient.get<Skill>(`${BASE}/${encodeURIComponent(id)}`),
  create: (req: SkillWriteRequest) => ApiClient.post<Skill>(BASE, req),
  update: (id: string, req: SkillWriteRequest) =>
    ApiClient.put<Skill>(`${BASE}/${encodeURIComponent(id)}`, req),
  delete: (id: string) => ApiClient.delete<void>(`${BASE}/${encodeURIComponent(id)}`),
  // Agent bindings
  listAgents: (skillId: string) =>
    ApiClient.get<SkillAgentBinding[]>(`${BASE}/${encodeURIComponent(skillId)}/agents`),
  attachAgent: (skillId: string, agentId: string, priority?: number) =>
    ApiClient.post<void>(
      `${BASE}/${encodeURIComponent(skillId)}/agents/${encodeURIComponent(agentId)}`
        + (priority != null ? `?priority=${priority}` : ''),
    ),
  detachAgent: (skillId: string, agentId: string) =>
    ApiClient.delete<void>(
      `${BASE}/${encodeURIComponent(skillId)}/agents/${encodeURIComponent(agentId)}`,
    ),
};
