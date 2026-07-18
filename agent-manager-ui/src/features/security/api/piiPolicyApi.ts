import { ApiClient } from '../../../shared/api/client';
import type { 
  PiiPolicy, 
  PiiPolicyCreateRequest, 
  PiiAuditLogEntry 
} from '../types/pii.types';

const BASE_PATH = '/v1/pii-policies';

export const piiPolicyApi = {
  // Global Policy Dictionary
  getPolicies: () => ApiClient.get<PiiPolicy[]>(BASE_PATH),
  createPolicy: (policy: PiiPolicyCreateRequest) => ApiClient.post<PiiPolicy>(BASE_PATH, policy),
  deletePolicy: (id: string) => ApiClient.delete<void>(`${BASE_PATH}/${id}`),

  // Agent Bindings
  getAgentBindings: (agentId: string) => ApiClient.get<string[]>(`${BASE_PATH}/agents/${agentId}`),
  bindPolicy: (agentId: string, policyId: string) => ApiClient.post<void>(`${BASE_PATH}/agents/${agentId}/bind/${policyId}`),
  unbindPolicy: (agentId: string, policyId: string) => ApiClient.delete<void>(`${BASE_PATH}/agents/${agentId}/unbind/${policyId}`),

  // Audit Logs
  getAuditLog: (agentId?: string) => {
    const url = agentId ? `${BASE_PATH}/audit-log?agentId=${encodeURIComponent(agentId)}` : `${BASE_PATH}/audit-log`;
    return ApiClient.get<PiiAuditLogEntry[]>(url);
  }
};
