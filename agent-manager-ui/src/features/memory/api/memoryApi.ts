import { ApiClient } from '../../../shared/api/client';

export interface MemoryStats {
    totalMemories: number;
    userId: string;
    topics: string[];
    [key: string]: any;
}

export interface MemoryEntry {
    id: string;
    memory: string;
    topics: string[] | null;
    tier: string;
    agentId: string | null;
    createdAt: string;
}

export interface IngestionJob {
    id: string;
    document: string;
    status: string;
    chunks: number;
    entitiesExtracted: number;
}

export const memoryApi = {
    addMemory: (content: string) =>
        ApiClient.post<{ message: string }>('/memories', { content }),

    searchMemories: (query: string) =>
        ApiClient.get<string[]>(`/memories?query=${encodeURIComponent(query)}`),

    deleteMemories: (ids: string[]) =>
        ApiClient.delete<void>('/memories', ids),

    optimizeMemories: (userId?: string) =>
        ApiClient.post<{ jobId: string }>(`/memories/optimize${userId ? `?userId=${encodeURIComponent(userId)}` : ''}`),

    getMemoryStats: (userId?: string) =>
        ApiClient.get<MemoryStats>(`/memories/stats${userId ? `?userId=${encodeURIComponent(userId)}` : ''}`),

    getMemoryTopics: (userId?: string) =>
        ApiClient.get<string[]>(`/memories/topics${userId ? `?userId=${encodeURIComponent(userId)}` : ''}`),

    getTimeline: (userId: string) =>
        ApiClient.get<MemoryEntry[]>(`/memories/timeline/${encodeURIComponent(userId)}`),

    tagMemory: (memoryId: string, tags: string[]) =>
        ApiClient.put<void>(`/memories/${encodeURIComponent(memoryId)}/tags`, tags),

    exportMemories: (userId: string) =>
        ApiClient.get<MemoryEntry[]>(`/memories/export/${encodeURIComponent(userId)}`),

    rtbfWipe: (userId: string) =>
        ApiClient.delete<void>(`/v1/memories/rtbf/${encodeURIComponent(userId)}`),
};
