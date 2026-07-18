import { ApiClient } from '../../../shared/api/client';

const BASE = '/alerts/rules';

export type AlertCondition = 'GT' | 'GTE' | 'LT' | 'LTE' | 'EQ';
export type AlertSeverity = 'INFO' | 'WARNING' | 'CRITICAL';

export interface AlertRule {
  id: string;
  name: string;
  description?: string | null;
  metricName: string;
  condition: AlertCondition;
  threshold: number;
  windowSeconds: number;
  severity: AlertSeverity;
  enabled: boolean;
  notificationChannel?: string | null;
  orgId?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface AlertRuleWriteRequest {
  id?: string;
  name: string;
  description?: string;
  metricName: string;
  condition: AlertCondition;
  threshold: number;
  windowSeconds: number;
  severity: AlertSeverity;
  enabled: boolean;
  notificationChannel?: string;
}

export const alertRulesApi = {
  list: () => ApiClient.get<AlertRule[]>(BASE),
  get: (id: string) => ApiClient.get<AlertRule>(`${BASE}/${id}`),
  create: (req: AlertRuleWriteRequest) => ApiClient.post<AlertRule>(BASE, req),
  update: (id: string, req: AlertRuleWriteRequest) =>
    ApiClient.put<AlertRule>(`${BASE}/${id}`, req),
  delete: (id: string) => ApiClient.delete<void>(`${BASE}/${id}`),
};
