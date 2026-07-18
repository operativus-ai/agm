import { ApiClient } from '../../../shared/api/client';
import type {
  ProviderCredentialRequest,
  ProviderCredentialResponse,
  ProviderCredentialTestRequest,
  ProviderCredentialTestResponse,
} from '../types/provider-credentials.types';

const BASE = '/v1/provider-credentials';

export class ProviderCredentialsApi {
  static async list(): Promise<ProviderCredentialResponse[]> {
    return ApiClient.get<ProviderCredentialResponse[]>(BASE);
  }

  static async get(id: string): Promise<ProviderCredentialResponse> {
    return ApiClient.get<ProviderCredentialResponse>(`${BASE}/${id}`);
  }

  static async create(req: ProviderCredentialRequest): Promise<ProviderCredentialResponse> {
    return ApiClient.post<ProviderCredentialResponse>(BASE, req);
  }

  static async update(id: string, req: ProviderCredentialRequest): Promise<ProviderCredentialResponse> {
    return ApiClient.put<ProviderCredentialResponse>(`${BASE}/${id}`, req);
  }

  static async delete(id: string): Promise<void> {
    return ApiClient.delete<void>(`${BASE}/${id}`);
  }

  /**
   * Live "test connection" probe. Validates a key (the supplied one, or the stored key when
   * `apiKey` is blank) against `model` with one tiny completion. Always resolves (HTTP 200);
   * inspect `success`/`message` on the result.
   * Maps to Backend: POST /api/v1/provider-credentials/test
   */
  static async test(req: ProviderCredentialTestRequest): Promise<ProviderCredentialTestResponse> {
    return ApiClient.post<ProviderCredentialTestResponse>(`${BASE}/test`, req);
  }
}
