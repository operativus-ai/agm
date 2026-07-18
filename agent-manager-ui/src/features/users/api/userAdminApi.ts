import type { PaginatedResponse, UserAdmin, UserCreateRequest, UserUpdateRequest } from '../../../shared/types/api';
import { ApiClient } from '../../../shared/api/client';

const BASE = '/admin/users';

export type BulkCreateItemStatus = 'created' | 'already_exists';

export interface BulkCreateItem {
  id: string | null;
  username: string;
  email: string;
  status: BulkCreateItemStatus;
}

export interface BulkCreateRequest {
  users: UserCreateRequest[];
}

export interface BulkCreateResponse {
  items: BulkCreateItem[];
  created: number;
  alreadyExisted: number;
}

export class UserAdminApi {
  static async listUsers(page = 0, size = 20): Promise<PaginatedResponse<UserAdmin>> {
    return ApiClient.get<PaginatedResponse<UserAdmin>>(`${BASE}?page=${page}&size=${size}&sort=username`);
  }

  static async createUser(req: UserCreateRequest): Promise<UserAdmin> {
    return ApiClient.post<UserAdmin>(BASE, req);
  }

  static async updateUser(id: string, req: UserUpdateRequest): Promise<UserAdmin> {
    return ApiClient.put<UserAdmin>(`${BASE}/${id}`, req);
  }

  static async resetPassword(id: string, password: string): Promise<void> {
    return ApiClient.post<void>(`${BASE}/${id}/reset-password`, { password });
  }

  static async deleteUser(id: string): Promise<void> {
    return ApiClient.delete<void>(`${BASE}/${id}`);
  }

  /**
   * Bulk-creates users in a single request. Backend hashes the request body for
   * idempotency unless an explicit Idempotency-Key header is provided; passing
   * a per-modal-session UUID makes the second click on a stalled UI a no-op
   * instead of a partial duplicate.
   */
  static async bulkCreate(req: BulkCreateRequest, idempotencyKey?: string): Promise<BulkCreateResponse> {
    const headers: Record<string, string> = {};
    if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey;
    return ApiClient.request<BulkCreateResponse>(`${BASE}/bulk`, {
      method: 'POST',
      body: JSON.stringify(req),
      headers,
    });
  }
}
