import { ApiClient } from '../../../shared/api/client';

const BASE = '/compliance';

export type ErasureRequestStatus =
  | 'PENDING'
  | 'IN_PROGRESS'
  | 'COMPLETED'
  | 'PARTIAL'
  | 'FAILED';

export interface ErasureRequest {
  id: string;
  userId: string;
  requestedBy: string;
  status: ErasureRequestStatus;
  requestedAt: string;
  startedAt: string | null;
  completedAt: string | null;
  summary: Record<string, unknown> | null;
  errorMessage: string | null;
}

export const complianceApi = {
  exportUserData: (userId: string) =>
    ApiClient.get<Blob>(`${BASE}/export/${userId}`),

  submitErasureRequest: (userId: string): Promise<{ jobId: string }> =>
    ApiClient.post<{ jobId: string }>(`${BASE}/erasure-requests?userId=${encodeURIComponent(userId)}`),

  eraseUserData: (userId: string) =>
    ApiClient.delete<void>(`${BASE}/erase/${userId}`),

  listErasureRequests: (userId: string) =>
    ApiClient.get<ErasureRequest[]>(`${BASE}/erasure-requests?userId=${encodeURIComponent(userId)}`),
};
