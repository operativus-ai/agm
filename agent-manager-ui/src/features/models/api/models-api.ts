import { ApiClient } from '../../../shared/api/client';
import type { ModelConfig, ModelPingResult, ModelRequest } from '../types/models.types';
import type { PaginatedResponse } from '../../../shared/types/api';

const API_BASE_URL = '/models';

export class ModelsApi {
  
  /**
   * Fetch registered models with server-side pagination.
   * Maps to Backend: GET /api/models?page=N&size=M
   */
  static async getModels(params?: { page?: number; size?: number }): Promise<PaginatedResponse<ModelConfig>> {
    const sp = new URLSearchParams();
    sp.append('page', String(params?.page ?? 0));
    sp.append('size', String(params?.size ?? 20));
    return ApiClient.get<PaginatedResponse<ModelConfig>>(`${API_BASE_URL}?${sp.toString()}`);
  }

  /**
   * Fetch the provider's live model-id catalog (for autocomplete in the model form).
   * Maps to Backend: GET /api/v1/models/catalog/{provider}. Returns empty modelIds when
   * no ProviderCredential is configured or the provider call fails (never throws server-side).
   */
  static async getModelCatalog(provider: string): Promise<{ provider: string; modelIds: string[] }> {
    return ApiClient.get<{ provider: string; modelIds: string[] }>(
      `/v1/models/catalog/${encodeURIComponent(provider)}`,
    );
  }

  static async createModel(data: ModelRequest): Promise<ModelConfig> {
    return ApiClient.post<ModelConfig>(API_BASE_URL, data);
  }

  static async updateModel(id: string, data: Partial<ModelRequest>): Promise<ModelConfig> {
    return ApiClient.patch<ModelConfig>(`${API_BASE_URL}/${id}`, data);
  }

  static async deleteModel(id: string): Promise<void> {
    return ApiClient.delete<void>(`${API_BASE_URL}/${id}`);
  }

  /**
   * §6 M-12 Phase 4: explicitly clear a model's per-model rate-limit override.
   * Returns 204 No Content. Idempotent — calling on a model whose RPM is already
   * null is fine. PUT /models/{id} cannot express clear (its update rule treats
   * null as "keep existing"), hence the dedicated DELETE verb.
   */
  static async clearRateLimit(id: string): Promise<void> {
    return ApiClient.delete<void>(`${API_BASE_URL}/${id}/rate-limit`);
  }

  static async testConnection(data: ModelRequest): Promise<void> {
    return ApiClient.post<void>(`${API_BASE_URL}/test`, data);
  }

  /**
   * Fire a liveness probe against an existing saved model. Always 200 OK; failure
   * is encoded in the response body's `available=false` + `errorMessage`. The
   * backend persists the outcome to `models.available` + `models.last_pinged_at`,
   * so refreshing the row from the response keeps the local state in sync with
   * what the scheduled poller would observe next.
   * Maps to Backend: POST /api/models/{id}/test
   */
  static async pingModel(id: string): Promise<ModelPingResult> {
    return ApiClient.post<ModelPingResult>(`${API_BASE_URL}/${id}/test`, {});
  }

  /**
   * Clone an existing model into a new row. The encrypted apiKey + provider
   * config + capability flags carry over; liveness state and default-slot
   * assignment do not (clones start unprobed and unassigned). `newName` is
   * optional; the backend defaults to "<source name> (Clone)" when omitted.
   * Maps to Backend: POST /api/models/{id}/clone[?newName=...]
   */
  static async cloneModel(id: string, newName?: string): Promise<ModelConfig> {
    const qs = newName && newName.trim() ? `?newName=${encodeURIComponent(newName.trim())}` : '';
    return ApiClient.post<ModelConfig>(`${API_BASE_URL}/${id}/clone${qs}`, {});
  }
}
