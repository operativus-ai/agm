import { ApiClient } from '../../../shared/api/client';

const BASE_PATH = '/v1/agents';

export type CredentialType = 'OAUTH2' | 'API_KEY' | 'JWT' | 'BEARER';

/**
 * Credential metadata returned by the server.
 *
 * SECURITY NOTE: the `encryptedSecret` field may come back masked or
 * omitted from the server depending on environment policy. The UI MUST
 * NOT render any value from it as plaintext — treat it as a presence
 * flag only. Secret rotation is done by DELETE + POST with a fresh
 * secret, not by reading-and-editing an existing one.
 */
export interface AgentCredential {
  id: string;
  agentId: string;
  credentialType: CredentialType;
  providerName: string;
  encryptedSecret?: string | null;
  scopes?: string | null;
  tokenEndpoint?: string | null;
  clientId?: string | null;
  expiresAt?: string | null;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface AgentCredentialCreateRequest {
  credentialType: CredentialType;
  providerName: string;
  /** Plaintext secret submitted once at creation; never re-displayed. */
  encryptedSecret: string;
  scopes?: string;
  tokenEndpoint?: string;
  clientId?: string;
  expiresAt?: string;
  enabled?: boolean;
}

/**
 * Update metadata only. The encryptedSecret field is intentionally
 * absent — if you want to rotate a credential's secret, delete + recreate.
 * This avoids accidental plaintext-roundtrip of sensitive material.
 */
export interface AgentCredentialMetadataUpdateRequest {
  credentialType?: CredentialType;
  providerName?: string;
  scopes?: string;
  tokenEndpoint?: string;
  clientId?: string;
  expiresAt?: string | null;
  enabled?: boolean;
}

export const agentCredentialsApi = {
  list: (agentId: string) =>
    ApiClient.get<AgentCredential[]>(`${BASE_PATH}/${agentId}/credentials`),
  get: (agentId: string, credentialId: string) =>
    ApiClient.get<AgentCredential>(`${BASE_PATH}/${agentId}/credentials/${credentialId}`),
  create: (agentId: string, req: AgentCredentialCreateRequest) =>
    ApiClient.post<AgentCredential>(`${BASE_PATH}/${agentId}/credentials`, req),
  updateMetadata: (
    agentId: string,
    credentialId: string,
    req: AgentCredentialMetadataUpdateRequest
  ) =>
    ApiClient.put<AgentCredential>(
      `${BASE_PATH}/${agentId}/credentials/${credentialId}`,
      req
    ),
  delete: (agentId: string, credentialId: string) =>
    ApiClient.delete<void>(`${BASE_PATH}/${agentId}/credentials/${credentialId}`),
};
