import { ApiClient } from '../../../shared/api/client';
import type { PaginatedResponse } from '../../../shared/types/api';

const BASE = '/v1/agents';

export interface AgentReflection {
  id: string;
  agentId: string;
  content: string | null;
  sourceRunId: string | null;
  createdAt: string;
}

export const agentReflectionsApi = {
  list: (agentId: string, page = 0, size = 20) => {
    const sp = new URLSearchParams();
    sp.set('page', String(page));
    sp.set('size', String(size));
    return ApiClient.get<PaginatedResponse<AgentReflection>>(
      `${BASE}/${encodeURIComponent(agentId)}/reflections?${sp.toString()}`,
    );
  },
};
