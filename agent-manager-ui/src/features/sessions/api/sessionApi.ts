import { ApiClient } from '../../../shared/api/client';

export interface AgentRun {
    id: string;
    agentId: string;
    sessionId: string;
    userId: string;
    status: string;
    startedAt: string;
    completedAt?: string;
    metrics?: Record<string, any>;
    logOutput?: string;
    input?: string;
    output?: string;
}

export interface AgentSession {
    id: string;
    userId: string;
    orgId?: string | null;
    agentId: string;
    title?: string;
    sessionState?: Record<string, any> | null;
    createdAt: string;
    updatedAt: string;
    /** FE-only — BE AgentSession has no `status` field. SessionDetailsPage
     *  reads it and falls back to 'UNKNOWN' when undefined. Cleanup
     *  candidate: either remove this field or add a BE column. See
     *  docs/analysis/api-sync.md (audit 2026-05-10). */
    status?: string;
}

export interface PaginatedResponse<T> {
    content: T[];
    pageable: any;
    totalElements: number;
    totalPages: number;
    size: number;
    number: number;
    numberOfElements: number;
    empty: boolean;
}

export const sessionApi = {
    listSessions: (params?: { page?: number; size?: number; userId?: string; agentId?: string }) => {
        const sp = new URLSearchParams();
        sp.append('page', String(params?.page ?? 0));
        sp.append('size', String(params?.size ?? 20));
        if (params?.userId) sp.append('userId', params.userId);
        if (params?.agentId) sp.append('agentId', params.agentId);
        return ApiClient.get<PaginatedResponse<AgentSession>>(`/sessions?${sp.toString()}`);
    },
    
    getSession: (sessionId: string) => 
        ApiClient.get<AgentSession>(`/sessions/${sessionId}`),
        
    getSessionRuns: (sessionId: string) => 
        ApiClient.get<AgentRun[]>(`/sessions/${sessionId}/runs`),
        
    deleteSession: (sessionId: string) => 
        ApiClient.delete<void>(`/sessions/${sessionId}`)
};
