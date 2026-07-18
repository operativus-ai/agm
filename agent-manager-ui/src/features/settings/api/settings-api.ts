import { ApiClient } from '../../../shared/api/client';

export type SettingsMap = Record<string, string>;

/** Result of a re-embed (POST /api/v1/admin/embeddings/backfill). Mirrors the BE EmbeddingBackfillResponse. */
export interface EmbeddingBackfillResponse {
    orgId: string;
    storeTypeFilter: string | null;
    scanned: number;
    reembedded: number;
    dimensions: number;
    byStoreType: Record<string, number>;
}

export const settingsApi = {
    /**
     * Gets all global settings.
     */
    getAllSettings: async (): Promise<SettingsMap> => {
        return ApiClient.get<SettingsMap>('/v1/settings');
    },

    /**
     * Updates multiple settings.
     */
    updateSettings: async (updates: SettingsMap): Promise<void> => {
        await ApiClient.put<void>('/v1/settings', updates);
    },

    /**
     * Re-embeds the caller org's pgvector rows under the currently-elected embedding model.
     * Run after changing the Default Embedding Model so existing chunks match the new model.
     * ADMIN-only; org-scoped. Optional storeType ('KB' | 'MEMORY') narrows the scope.
     */
    reembedVectors: async (storeType?: 'KB' | 'MEMORY'): Promise<EmbeddingBackfillResponse> => {
        const qs = storeType ? `?storeType=${storeType}` : '';
        return ApiClient.post<EmbeddingBackfillResponse>(`/v1/admin/embeddings/backfill${qs}`);
    },
};
