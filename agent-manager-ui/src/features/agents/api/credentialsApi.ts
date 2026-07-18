import { ApiClient } from '../../../shared/api/client';

export interface AgentCredentialDTO {
  id?: string;
  agentId?: string;
  credentialType: string;
  providerName: string;
  encryptedSecret: string;
  scopes?: string;
  tokenEndpoint?: string;
  clientId?: string;
  enabled: boolean;
}

const path = (agentId: string, credentialId?: string): string =>
  credentialId
    ? `/v1/agents/${agentId}/credentials/${credentialId}`
    : `/v1/agents/${agentId}/credentials`;

export const credentialsApi = {
  list(agentId: string): Promise<AgentCredentialDTO[]> {
    return ApiClient.get<AgentCredentialDTO[]>(path(agentId));
  },

  create(agentId: string, credential: Partial<AgentCredentialDTO>): Promise<AgentCredentialDTO> {
    return ApiClient.post<AgentCredentialDTO>(path(agentId), credential);
  },

  delete(agentId: string, credentialId: string): Promise<void> {
    return ApiClient.delete<void>(path(agentId, credentialId));
  },
};
