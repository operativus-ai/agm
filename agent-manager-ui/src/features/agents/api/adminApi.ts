import type { AgentConfig, PaginatedResponse, AgentRun, AgentAudit, AgentTopology } from '../../../shared/types/api';
import { ApiClient } from '../../../shared/api/client';

const API_BASE_URL = '/admin/agents';

export class AgentAdminApi {
  
  /**
   * Fetch all registered agent configurations with pagination.
   * Maps to Backend: GET /api/admin/agents
   */
  static async getAgents(page = 0, size = 20, includeInactive = false): Promise<PaginatedResponse<AgentConfig>> {
    return ApiClient.get<PaginatedResponse<AgentConfig>>(`${API_BASE_URL}?page=${page}&size=${size}&includeInactive=${includeInactive}`);
  }

  /**
   * Create a new agent.
   * Maps to Backend: POST /api/admin/agents
   */
  static async createAgent(agent: AgentConfig): Promise<AgentConfig> {
    return ApiClient.post<AgentConfig>(API_BASE_URL, agent);
  }

  /**
   * Update an existing agent.
   * Maps to Backend: PUT /api/admin/agents/{id}
   */
  static async updateAgent(id: string, agent: AgentConfig): Promise<AgentConfig> {
    return ApiClient.put<AgentConfig>(`${API_BASE_URL}/${id}`, agent);
  }

  /**
   * Fetch paginated execution history for an agent.
   * Maps to Backend: GET /api/admin/agents/{id}/history
   */
  static async getAgentHistory(id: string, page = 0, size = 20): Promise<PaginatedResponse<AgentRun>> {
    return ApiClient.get<PaginatedResponse<AgentRun>>(`${API_BASE_URL}/${id}/history?page=${page}&size=${size}`);
  }

  /**
   * Fetch recent logs for an agent.
   * Maps to Backend: GET /api/admin/agents/{id}/logs
   */
  static async getAgentLogs(id: string): Promise<string[]> {
    return ApiClient.get<string[]>(`${API_BASE_URL}/${id}/logs`);
  }

  /**
   * Fetch paginated audit history for an agent.
   * Maps to Backend: GET /api/admin/agents/{id}/audit
   */
  static async getAgentAuditHistory(id: string, page = 0, size = 20): Promise<PaginatedResponse<AgentAudit>> {
    return ApiClient.get<PaginatedResponse<AgentAudit>>(`${API_BASE_URL}/${id}/audit?page=${page}&size=${size}`);
  }

  /**
   * Export an agent configuration.
   * Maps to Backend: GET /api/admin/agents/{id}/export
   */
  static async exportAgent(id: string): Promise<AgentConfig> {
    return ApiClient.get<AgentConfig>(`${API_BASE_URL}/${id}/export`);
  }

  /**
   * Import an agent configuration.
   * Maps to Backend: POST /api/admin/agents/import
   */
  static async importAgent(agent: AgentConfig): Promise<AgentConfig> {
    return ApiClient.post<AgentConfig>(`${API_BASE_URL}/import`, agent);
  }

  /**
   * Fetch agent topology graph data.
   * Maps to Backend: GET /api/admin/agents/{id}/topology
   */
  static async getAgentTopology(id: string): Promise<AgentTopology> {
    return ApiClient.get<AgentTopology>(`${API_BASE_URL}/${id}/topology`);
  }

  static async cancelRun(runId: string): Promise<void> {
    return ApiClient.post<void>(`${API_BASE_URL}/runs/${runId}/cancel`, {});
  }

  /**
   * Lists every persisted version-snapshot for an agent. Each snapshot is a full
   * AgentConfig the operator can compare or roll back to via {@link rollbackAgent}.
   * Maps to Backend: GET /api/admin/agents/{id}/versions
   */
  static async getAgentVersions(id: string): Promise<AgentConfig[]> {
    return ApiClient.get<AgentConfig[]>(`${API_BASE_URL}/${id}/versions`);
  }

  /**
   * Rolls an agent back to the configuration captured in a specific audit
   * snapshot id (typically obtained from {@link getAgentVersions}). Returns the
   * resulting AgentConfig — same shape as updateAgent so callers can refresh
   * local state from the response without a second GET.
   * Maps to Backend: POST /api/admin/agents/{id}/rollback/{auditId}
   */
  static async rollbackAgent(id: string, auditId: string): Promise<AgentConfig> {
    return ApiClient.post<AgentConfig>(`${API_BASE_URL}/${id}/rollback/${encodeURIComponent(auditId)}`, {});
  }
}
