import { ApiClient } from './client';

export interface ToolItem {
    id: string;
    label: string;
    desc: string;
    category?: string;
    categoryLabel?: string;
}

export const ToolsApi = {
    /**
     * Gets all dynamically registered tools (both standard system tools and external MCP tools).
     */
    getTools: async (): Promise<ToolItem[]> => {
        return ApiClient.get<ToolItem[]>('/tools');
    }
};
