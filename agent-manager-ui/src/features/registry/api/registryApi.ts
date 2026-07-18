import { ApiClient } from '../../../shared/api/client';

export interface CodeRegistryResponse {
    agents?: Record<string, any>;
    teams?: Record<string, any>;
    tools?: Record<string, any>;
    // Extend based on what the backend actually returns
    [key: string]: any;
}

export const registryApi = {
    getCodeRegistry: async (): Promise<CodeRegistryResponse> => {
        const [agents, teams] = await Promise.all([
            ApiClient.get<Record<string, any>>('/v1/registry/agents/code').catch(() => ({})),
            ApiClient.get<Record<string, any>>('/v1/registry/teams/code').catch(() => ({}))
        ]);
        return { agents, teams };
    }
};
