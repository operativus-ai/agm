import { ApiClient } from '../../../shared/api/client';
import type { AgentSummary, KnowledgeBase } from '../../../shared/types/api';

const API_BASE_URL = '/v1/knowledge-bases';

export class KnowledgeBasesApi {
  /**
   * List all knowledge bases.
   */
  static async getAll(): Promise<KnowledgeBase[]> {
    return ApiClient.get<KnowledgeBase[]>(API_BASE_URL);
  }

  /**
   * Create a new knowledge base.
   */
  static async create(data: Omit<KnowledgeBase, 'id' | 'createdAt' | 'updatedAt'>): Promise<KnowledgeBase> {
    return ApiClient.post<KnowledgeBase>(API_BASE_URL, data);
  }

  /**
   * Update an existing knowledge base.
   */
  static async update(id: string, data: Partial<Omit<KnowledgeBase, 'id' | 'createdAt' | 'updatedAt'>>): Promise<KnowledgeBase> {
    return ApiClient.put<KnowledgeBase>(`${API_BASE_URL}/${id}`, data);
  }

  /**
   * Delete a knowledge base by ID.
   */
  static async delete(id: string): Promise<void> {
    return ApiClient.delete<void>(`${API_BASE_URL}/${id}`);
  }

  static async getAssignedAgents(id: string): Promise<AgentSummary[]> {
    return ApiClient.get<AgentSummary[]>(`${API_BASE_URL}/${id}/agents`);
  }

  static async assignAgent(kbId: string, agentId: string): Promise<void> {
    return ApiClient.post<void>(`${API_BASE_URL}/${kbId}/agents/${agentId}`, {});
  }

  static async removeAgent(kbId: string, agentId: string): Promise<void> {
    return ApiClient.delete<void>(`${API_BASE_URL}/${kbId}/agents/${agentId}`);
  }
}
