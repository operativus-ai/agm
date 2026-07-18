import { ApiClient } from '../../../shared/api/client';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';

export type McpTransport = 'SSE' | 'STREAMABLE_HTTP';

export interface ExtensionRegistration {
  id?: string;
  name: string;
  type: 'MCP' | 'WEBHOOK';
  url: string;
  description?: string;
  active: boolean;
  version?: number;
  /** MCP transport. Defaults to SSE on the backend when omitted. */
  transport?: McpTransport;
  /** Write-only bearer secret. Sent on create/update; never returned by the API. */
  auth?: string;
  /** Read-only masked hint (****last4), or null when no secret is set. */
  authPreview?: string | null;
}

export interface ValidationResponse {
  success: boolean;
  message: string;
  latencyMs?: number;
}

export const extensionApi = {
  getExtensions: async (): Promise<ExtensionRegistration[]> => {
    return ApiClient.get<ExtensionRegistration[]>('/v1/extensions');
  },

  registerExtension: async (data: ExtensionRegistration): Promise<ExtensionRegistration> => {
    return ApiClient.post<ExtensionRegistration>('/v1/extensions', data);
  },

  deleteExtension: async (id: string): Promise<void> => {
    return ApiClient.delete<void>(`/v1/extensions/${id}`);
  },

  // Proxies network check through backend to avoid CORS and Zero-Trust violations
  validateConnection: async (url: string, type: 'MCP' | 'WEBHOOK'): Promise<ValidationResponse> => {
    return ApiClient.post<ValidationResponse>('/v1/extensions/validate', { url, type });
  }
};

// React Query Hooks
export const useExtensions = () => {
  return useQuery({
    queryKey: ['extensions'],
    queryFn: extensionApi.getExtensions
  });
};

export const useValidateExtension = () => {
  return useMutation({
    mutationFn: (params: { url: string, type: 'MCP' | 'WEBHOOK' }) => 
      extensionApi.validateConnection(params.url, params.type)
  });
};

export const useRegisterExtension = () => {
  return useMutation({
    mutationFn: extensionApi.registerExtension
  });
};

export const useDeleteExtension = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: extensionApi.deleteExtension,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['extensions'] });
    },
  });
};
