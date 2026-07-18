import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { finopsApi } from '../api/finopsApi';
import type { ValuationRate } from '../api/finopsApi';
import { AgentsApi } from '../../agents/api/agents-api';

export type { HistoricalTrendPoint, CostAllocationEntry, RoiStats, ModelCostSlice, CacheImpactPoint } from '../api/finopsApi';

export const useValuationRates = () => {
    return useQuery({
        queryKey: ['finops', 'valuationRates'],
        queryFn: finopsApi.getValuationRates
    });
};

export const useUpdateValuationRate = () => {
    const queryClient = useQueryClient();
    return useMutation({
        mutationFn: (rate: ValuationRate) => finopsApi.updateValuationRate(rate),
        onSuccess: () => {
            queryClient.invalidateQueries({ queryKey: ['finops', 'valuationRates'] });
        }
    });
};

export const useActiveBurnRates = () => {
    return useQuery({
        queryKey: ['finops', 'activeBurnRates'],
        queryFn: finopsApi.getActiveBurnRates,
        refetchInterval: 5000 // Poll every 5 seconds for live telemetry
    });
};

export const useUpdateAgentBaseline = () => {
    return useMutation({
        mutationFn: ({ agentId, baselineUsdPerHour }: { agentId: string; baselineUsdPerHour: number }) =>
            finopsApi.updateAgentBaseline(agentId, { baselineUsdPerHour }),
    });
};

/**
 * Fetches trailing N-day historical burn rate trend data for the Executive Dashboard chart.
 * Data is derived from Postgres GROUP BY DATE aggregation on agent_runs.
 * Zero-filled for missing days — always returns exactly `days` data points.
 */
export const useHistoricalTrends = (days = 7) => {
    return useQuery({
        queryKey: ['finops', 'historicalTrends', days],
        queryFn: () => finopsApi.getHistoricalTrends(days),
        staleTime: 300_000, // 5-minute cache — historical data changes slowly
    });
};

/**
 * Fetches cost allocation breakdown by agent and org dimension for the donut chart.
 * Returns allocation percentages computed at the database level via GROUP BY aggregation.
 */
export const useCostAllocations = (days = 7) => {
    return useQuery({
        queryKey: ['finops', 'costAllocations', days],
        queryFn: () => finopsApi.getCostAllocations(days),
        staleTime: 300_000, // 5-minute cache — allocation distribution changes slowly
    });
};

/**
 * Fetches Semantic Cache ROI and Embedding cost statistics from the backend Micrometer meters.
 * Values are process-lifetime accumulators reset on server restart.
 * Polled every 30 seconds for a near-real-time view on the dashboard.
 */
export const useRoiStats = () => {
    return useQuery({
        queryKey: ['finops', 'roiStats'],
        queryFn: finopsApi.getRoiStats,
        refetchInterval: 30_000,
    });
};

/**
 * Fetches cost allocation breakdown by LLM vendor model for the model cost slice chart.
 * Returns allocation percentages and estimated USD per model, computed at the database level.
 */
export const useModelCostAllocations = (days = 7) => {
    return useQuery({
        queryKey: ['finops', 'modelCostAllocations', days],
        queryFn: () => finopsApi.getModelCostAllocations(days),
        staleTime: 300_000,
    });
};

/**
 * Fetches trailing N-day cache impact time-series for the stacked area chart.
 * Shows total prompts versus cache-deflected prompts over the trailing window.
 */
export const useCacheImpactSeries = (days = 7) => {
    return useQuery({
        queryKey: ['finops', 'cacheImpact', days],
        queryFn: () => finopsApi.getCacheImpactSeries(days),
        staleTime: 300_000,
    });
};

/**
 * Fetches the full registry of agents for use in dropdowns and selectors.
 * Cached via React Query to prevent redundant network calls across FinOps components.
 */
export const useAgentList = () => {
    return useQuery({
        queryKey: ['agents', 'list'],
        queryFn: () => AgentsApi.getAgents(false),
        staleTime: 60_000, // Agent registry is stable; 1 minute cache
    });
};
