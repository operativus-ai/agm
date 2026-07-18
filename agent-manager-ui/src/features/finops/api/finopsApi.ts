import { ApiClient } from '../../../shared/api/client';

export interface TeamManifest {
    teamId: string;
    humanLead: string;
    maxDailySpend: number;
    minSpendingAuthority: number;
    allowedCapabilities: string[];
    agents: Record<string, {
        agentId: string;
        role: string;
        capabilities: string[];
        requiresPiiRedaction: boolean;
    }>;
}

export interface ValuationRate {
    modelId: string;
    inputRatePerKTokens: number;
    outputRatePerKTokens: number;
    /** USD per 1K cached prompt tokens. 0 = not configured (billed at full input rate). */
    cachedInputRatePerKTokens: number;
    /** USD per 1K reasoning tokens (o1/thinking models). 0 = not configured (billed at output rate). */
    reasoningRatePerKTokens: number;
}

export interface RoiStats {
    totalCacheSavingsUsd: number;
    totalEmbeddingCostUsd: number;
    netRoiUsd: number;
}

export interface ActiveWindow {
    sessionId: string;
    cumulativeUsd: number;
}

export interface BaselineRequest {
    baselineUsdPerHour: number;
}

export interface HistoricalTrendPoint {
    date: string;
    runCount: number;
    estimatedUsd: number;
}

export interface CostAllocationEntry {
    dimension: 'agent' | 'org';
    label: string;
    runCount: number;
    allocationPercent: number;
}

export interface ModelCostSlice {
    modelId: string;
    runCount: number;
    allocationPercent: number;
    estimatedUsd: number;
}

export interface CacheImpactPoint {
    date: string;
    totalPrompts: number;
    cacheHits: number;
}

export const finopsApi = {
    // Maps conceptually to TeamsController or a dedicated GatewaysController
    getTeamManifests: () => ApiClient.get<TeamManifest[]>('/v1/teams/manifests'),
    updateTeamManifest: (teamId: string, manifest: TeamManifest) => ApiClient.patch<TeamManifest>(`/v1/teams/${teamId}/manifest`, manifest),

    // FinOps Admin Controller Endpoints
    getValuationRates: () => ApiClient.get<ValuationRate[]>('/v1/finops/valuation-rates'),
    updateValuationRate: (rate: ValuationRate) => ApiClient.put<ValuationRate>('/v1/finops/valuation-rates', rate),
    getActiveBurnRates: () => ApiClient.get<ActiveWindow[]>('/v1/finops/burn-rates/active'),
    updateAgentBaseline: (agentId: string, payload: BaselineRequest) => ApiClient.put<{ agentId: string, baselineUsdPerHour: number }>(`/v1/finops/baselines/${agentId}`, payload),

    // Historical Analytics endpoints (OLAP read path)
    getHistoricalTrends: (days = 7) => ApiClient.get<HistoricalTrendPoint[]>(`/v1/finops/trends?days=${days}`),
    getCostAllocations: (days = 7) => ApiClient.get<CostAllocationEntry[]>(`/v1/finops/allocations?days=${days}`),

    // ROI Statistics (Semantic Cache + Embedding cost)
    getRoiStats: () => ApiClient.get<RoiStats>('/v1/finops/roi-stats'),

    // Model Cost Allocation (LLM Vendor Slicing)
    getModelCostAllocations: (days = 7) => ApiClient.get<ModelCostSlice[]>(`/v1/finops/allocations/by-model?days=${days}`),

    // Cache Impact Time-Series
    getCacheImpactSeries: (days = 7) => ApiClient.get<CacheImpactPoint[]>(`/v1/finops/cache-impact?days=${days}`),
};
