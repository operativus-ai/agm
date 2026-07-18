import { ApiClient } from '../../../shared/api/client';

const BASE = '/v1/observability';

export interface SloStatus {
  sloName: string;
  target: number;
  current: number;
  compliant: boolean;
  unit: string;
}

export const sloApi = {
  getStatus: () => ApiClient.get<SloStatus[]>(`${BASE}/slo-status`),
};
