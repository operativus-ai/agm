import React from 'react';
import { PageHeader } from '../../../shared/components/ui/PageHeader';
import { LuDollarSign } from 'react-icons/lu';
import { useQuery } from '@tanstack/react-query';
import { finopsApi } from '../api/finopsApi';
import { FinOpsOverview } from '../components/FinOpsOverview';
import { TeamManifestEditor } from '../components/TeamManifestEditor';
import { ModelValuationEditor } from '../components/ModelValuationEditor';
import { ActiveBurnRatesLocator } from '../components/ActiveBurnRatesLocator';
import { AgentBaselineConfiguration } from '../components/AgentBaselineConfiguration';
import { HistoricalBurnChart } from '../components/charts/HistoricalBurnChart';
import { CostAllocationChart } from '../components/charts/CostAllocationChart';
import { BudgetSaturationGauge } from '../components/charts/BudgetSaturationGauge';
import { ModelCostSliceChart } from '../components/charts/ModelCostSliceChart';
import { CacheImpactChart } from '../components/charts/CacheImpactChart';
import { useHistoricalTrends, useCostAllocations, useRoiStats, useActiveBurnRates } from '../hooks/useFinOps';
import { Tabs } from '../../../shared/components/ui/Tabs';
import { PageContainer } from '../../../shared/components/ui/PageContainer';

export const FinOpsPage: React.FC = () => {
    const { data: manifests = [], isLoading: manifestsLoading } = useQuery({
        queryKey: ['teamManifests'],
        queryFn: () => finopsApi.getTeamManifests()
    });

    const { data: trends = [], isLoading: trendsLoading, isError: trendsError } = useHistoricalTrends(7);
    const { data: allocations = [], isLoading: allocationsLoading, isError: allocationsError } = useCostAllocations(7);
    const { data: roiStats } = useRoiStats();
    const { data: activeBurnRates = [] } = useActiveBurnRates();

    const activeManifest = manifests.length > 0 ? manifests[0] : null;

    // Derive FinOps overview stats from existing API data (no hardcoded values)
    const todayStr = new Date().toISOString().slice(0, 10);
    const todayTrend = trends.find(t => t.date === todayStr);
    const derivedExpenditure = todayTrend?.estimatedUsd ?? 0;
    const derivedMaxDailySpend = activeManifest?.maxDailySpend ?? 0;
    const derivedActiveAgents = activeBurnRates.length;

    return (
        <PageContainer variant="dashboard">
            {/* Page Header */}
            <PageHeader
                icon={LuDollarSign}
                title="FinOps & Gateway Administration"
                subtitle="Zero-trust architecture enforcing NIST 2.0 CSF limits across all active agent execution streams."
            />

            {/* Tab Navigation */}
            <Tabs defaultValue="dashboard">
                <Tabs.List>
                    <Tabs.Trigger value="dashboard">Executive Dashboard</Tabs.Trigger>
                    <Tabs.Trigger value="guardrails">Financial Guardrails</Tabs.Trigger>
                    <Tabs.Trigger value="telemetry">Live Telemetry</Tabs.Trigger>
                    <Tabs.Trigger value="pricing">Pricing Engine</Tabs.Trigger>
                </Tabs.List>

                {/* ── Tab: Executive Dashboard ────────────────────────────── */}
                <Tabs.Content value="dashboard">
                    <div className="space-y-6">
                        <FinOpsOverview
                            tokenExpenditure={derivedExpenditure}
                            maxDailySpend={derivedMaxDailySpend}
                            activeAgents={derivedActiveAgents}
                            cacheSavingsUsd={roiStats?.totalCacheSavingsUsd ?? 0}
                            currentBurnRate={activeBurnRates.length > 0
                                ? activeBurnRates.reduce((sum, w) => sum + w.cumulativeUsd, 0) * 60
                                : 0}
                        />

                        {/* Row 1: Historical Burn + Budget Saturation Gauge */}
                        <div className="grid grid-cols-1 lg:grid-cols-4 gap-6">
                            <div className="lg:col-span-3">
                                <HistoricalBurnChart
                                    data={trends}
                                    isLoading={trendsLoading}
                                    isError={trendsError}
                                    days={7}
                                />
                            </div>
                            <div className="lg:col-span-1">
                                <BudgetSaturationGauge
                                    currentSpend={derivedExpenditure}
                                    budgetCeiling={derivedMaxDailySpend}
                                />
                            </div>
                        </div>

                        {/* Row 2: Cost Allocation + Model Slice + Cache Impact */}
                        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
                            <CostAllocationChart
                                data={allocations}
                                isLoading={allocationsLoading}
                                isError={allocationsError}
                            />
                            <ModelCostSliceChart days={7} />
                            <CacheImpactChart days={7} />
                        </div>

                    </div>
                </Tabs.Content>

                {/* ── Tab: Financial Guardrails ────────────────────────────── */}
                <Tabs.Content value="guardrails">
                    <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
                        <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl p-1">
                            <AgentBaselineConfiguration />
                        </div>
                        <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl p-1">
                            {manifestsLoading ? (
                                <div className="animate-pulse h-64 bg-obsidian-elevated rounded-xl" />
                            ) : activeManifest ? (
                                <TeamManifestEditor manifest={activeManifest} onSave={(m) => finopsApi.updateTeamManifest(m.teamId, m)} />
                            ) : (
                                <div className="p-8 text-center text-(--theme-muted) h-full flex items-center justify-center">
                                    No team manifests configured.
                                </div>
                            )}
                        </div>
                    </div>
                </Tabs.Content>

                {/* ── Tab: Live Telemetry ──────────────────────────────────── */}
                <Tabs.Content value="telemetry">
                    <ActiveBurnRatesLocator />
                </Tabs.Content>

                {/* ── Tab: Pricing Engine ─────────────────────────────────── */}
                <Tabs.Content value="pricing">
                    <ModelValuationEditor />
                </Tabs.Content>
            </Tabs>
        </PageContainer>
    );
};
