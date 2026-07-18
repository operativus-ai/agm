import { ApiClient } from '../../../shared/api/client';

export interface RetentionPolicies {
  sessions_days?: number;
  runs_days?: number;
  audit_days?: number;
  alerts_days?: number;
}

export const dataRetentionApi = {
  getPolicies(): Promise<RetentionPolicies> {
    return ApiClient.get<RetentionPolicies>('/admin/retention/policies');
  },

  purge(): Promise<Record<string, number>> {
    return ApiClient.post<Record<string, number>>('/admin/retention/purge', {});
  },
};
