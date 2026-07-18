import type { AgentConfig } from '../../../shared/types/api';
import { ApiClient } from '../../../shared/api/client';

const API_BASE_URL = '/agents';

export class AgentsApi {
  
  /**
   * Fetch all registered agent configurations.
   * Maps to Backend: GET /api/agents
   * @param includeInactive - If true, fetches both active and soft-deleted agents.
   */
  static async getAgents(includeInactive: boolean = false): Promise<AgentConfig[]> {
    return ApiClient.get<AgentConfig[]>(`${API_BASE_URL}?includeInactive=${includeInactive}`);
  }

  static async getAgent(id: string): Promise<AgentConfig> {
    return ApiClient.get<AgentConfig>(`${API_BASE_URL}/${id}`);
  }

  static async createAgent(data: Partial<AgentConfig>): Promise<AgentConfig> {
    return ApiClient.post<AgentConfig>('/admin/agents', data);
  }

  static async updateAgent(id: string, data: Partial<AgentConfig>): Promise<AgentConfig> {
    return ApiClient.put<AgentConfig>(`/admin/agents/${id}`, data);
  }

  static async deleteAgent(id: string, isTeam: boolean = false): Promise<void> {
    if (isTeam) {
      return ApiClient.delete<void>(`/v1/teams/${id}`);
    }
    return ApiClient.delete<void>(`/admin/agents/${id}`);
  }

  static async restoreAgent(id: string, isTeam: boolean = false): Promise<void> {
    if (isTeam) {
      // Team restore API would go here if implemented in the future
      return Promise.reject(new Error("Restoring teams is not yet supported."));
    }
    return ApiClient.post<void>(`/admin/agents/${id}/restore`, {});
  }

  static async loadKnowledge(agentId: string): Promise<{ jobId: string }> {
    return ApiClient.post<{ jobId: string }>(`${API_BASE_URL}/${agentId}/knowledge/load`, {});
  }

  static async clearCache(): Promise<string> {
    return ApiClient.post<string>(`${API_BASE_URL}/cache/clear`, {});
  }

  static async runBackground(agentId: string, request: any): Promise<any> {
    return ApiClient.post<any>(`${API_BASE_URL}/${agentId}/runs/background`, request);
  }

  /**
   * Fetch multiple run statuses in one call.
   * Pairs with GET /api/agents/{agentId}/runs/status?runIds=...
   * Returns entries for runIds that exist; missing runIds are simply absent
   * from the response (no 404 for partial results).
   */
  static async getRunStatusBatch(agentId: string, runIds: string[]): Promise<any[]> {
    if (runIds.length === 0) return [];
    const params = new URLSearchParams();
    for (const id of runIds) params.append('runIds', id);
    return ApiClient.get<any[]>(`${API_BASE_URL}/${agentId}/runs/status?${params.toString()}`);
  }

  static async cancelRun(agentId: string, runId: string): Promise<void> {
    return ApiClient.delete<void>(`${API_BASE_URL}/${agentId}/runs/${runId}`);
  }

  static async runAgentPlayground(agentId: string, prompt: string): Promise<string> {
    // Routes to the standard sync run endpoint (POST /api/agents/{id}/runs).
    // Previously called /runs/playground which doesn't exist on the backend
    // — that path 404'd in production. The Playground tab in AgentEditModal
    // just needs the final response text, which is RunResponse.content.
    const response = await ApiClient.post<{ content?: string; runId?: string }>(
      `${API_BASE_URL}/${agentId}/runs`,
      {
        message: prompt,
        sessionId: 'playground_' + Date.now(),
      }
    );
    return response.content ?? '';
  }

}
