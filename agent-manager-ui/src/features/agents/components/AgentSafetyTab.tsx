import React, { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
    CartesianGrid,
    Legend,
    Line,
    LineChart,
    ResponsiveContainer,
    Tooltip,
    XAxis,
    YAxis,
} from 'recharts';
import { LuRefreshCw } from 'react-icons/lu';
import { Alert } from '../../../shared/components/ui/Alert';
import { Button } from '../../../shared/components/ui/Button';
import { safetyAnalyticsApi, type SafetyHeatmapCell } from '../../observability/api/safetyAnalyticsApi';

interface AgentSafetyTabProps {
    agentId: string;
}

interface Row {
    bucket: string;
    avgScore: number;
    maxScore: number;
    flagged: number;
    total: number;
}

const formatDay = (iso: string): string => {
    const d = new Date(iso);
    return Number.isNaN(d.getTime()) ? iso : `${d.getUTCMonth() + 1}/${d.getUTCDate()}`;
};

const buildRows = (cells: SafetyHeatmapCell[], agentId: string): Row[] => {
    const filtered = cells
        .filter(c => c.agentId === agentId)
        .sort((a, b) => a.day.localeCompare(b.day));
    return filtered.map(c => ({
        bucket: formatDay(c.day),
        avgScore: Math.round(c.avgScore * 1000) / 1000,
        maxScore: Math.round(c.maxScore * 1000) / 1000,
        flagged: c.flagged,
        total: c.total,
    }));
};

export const AgentSafetyTab: React.FC<AgentSafetyTabProps> = ({ agentId }) => {
    const [windowDays, setWindowDays] = useState<number>(30);

    const { data, isLoading, isFetching, error, refetch } = useQuery({
        queryKey: ['agents', 'safety', agentId, windowDays],
        queryFn: () => safetyAnalyticsApi.get(windowDays),
        staleTime: 60_000,
    });

    const rows = useMemo(() => (data ? buildRows(data.cells, agentId) : []), [data, agentId]);

    const totals = useMemo(() => {
        if (rows.length === 0) return { runs: 0, flagged: 0, weightedAvg: 0, peakMax: 0 };
        let runs = 0;
        let flagged = 0;
        let weightedSum = 0;
        let peakMax = 0;
        for (const r of rows) {
            runs += r.total;
            flagged += r.flagged;
            weightedSum += r.avgScore * r.total;
            if (r.maxScore > peakMax) peakMax = r.maxScore;
        }
        return {
            runs,
            flagged,
            weightedAvg: runs > 0 ? weightedSum / runs : 0,
            peakMax,
        };
    }, [rows]);

    if (error) {
        return (
            <Alert severity="error" title="Failed to load safety data">
                {(error as Error).message}
            </Alert>
        );
    }

    return (
        <div className="space-y-4">
            <div className="flex items-center justify-between flex-wrap gap-3">
                <div className="flex items-center gap-3 text-xs">
                    <label className="text-(--theme-muted)">
                        Window
                        <select
                            className="ml-2 select select-xs bg-obsidian-base border-obsidian-stroke"
                            value={windowDays}
                            onChange={(e) => setWindowDays(Number(e.target.value))}
                        >
                            <option value={7}>Last 7 days</option>
                            <option value={30}>Last 30 days</option>
                            <option value={90}>Last 90 days</option>
                        </select>
                    </label>
                    <span className="text-(--theme-muted)">
                        {isLoading ? 'Loading…' : `${totals.runs.toLocaleString()} runs`}
                    </span>
                </div>
                <Button variant="outline" size="sm" onClick={() => refetch()} disabled={isFetching} className="gap-2">
                    {isFetching ? <span className="loading loading-spinner loading-sm" /> : <LuRefreshCw className="w-4 h-4" />}
                    Refresh
                </Button>
            </div>

            {isLoading && !data ? (
                <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
                    <div className="h-24 bg-obsidian-elevated/50 rounded-xl animate-pulse" />
                    <div className="h-24 bg-obsidian-elevated/50 rounded-xl animate-pulse" />
                    <div className="h-24 bg-obsidian-elevated/50 rounded-xl animate-pulse" />
                </div>
            ) : rows.length === 0 ? (
                <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl p-8 text-center text-(--theme-muted)">
                    No safety-scored runs for this agent in the selected window.
                </div>
            ) : (
                <>
                    <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
                        <KpiCard label="Avg risk (weighted)" value={totals.weightedAvg.toFixed(3)} subtitle={`${totals.runs.toLocaleString()} runs`} />
                        <KpiCard label="Peak risk" value={totals.peakMax.toFixed(3)} subtitle={totals.peakMax >= 0.7 ? 'high' : totals.peakMax >= 0.3 ? 'medium' : 'low'} />
                        <KpiCard label="Flagged runs" value={totals.flagged.toLocaleString()} subtitle={`${totals.runs > 0 ? ((totals.flagged / totals.runs) * 100).toFixed(1) : '0'}%`} />
                    </div>

                    <Card title="Risk score over time" subtitle="Per-day avg and peak risk for this agent.">
                        <ResponsiveContainer width="100%" height={280}>
                            <LineChart data={rows}>
                                <CartesianGrid strokeDasharray="3 3" stroke="rgba(148,163,184,0.15)" />
                                <XAxis dataKey="bucket" tick={{ fontSize: 11, fill: 'rgba(148,163,184,0.8)' }} />
                                <YAxis
                                    domain={[0, 1]}
                                    tick={{ fontSize: 11, fill: 'rgba(148,163,184,0.8)' }}
                                />
                                <Tooltip />
                                <Legend wrapperStyle={{ fontSize: 11 }} />
                                <Line type="monotone" dataKey="avgScore" stroke="#10b981" dot={false} strokeWidth={2} name="avg" />
                                <Line type="monotone" dataKey="maxScore" stroke="#ef4444" dot={false} strokeWidth={2} name="peak" />
                            </LineChart>
                        </ResponsiveContainer>
                    </Card>
                </>
            )}
        </div>
    );
};

const Card: React.FC<{ title: string; subtitle?: string; children: React.ReactNode }> = ({ title, subtitle, children }) => (
    <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl px-5 py-4">
        <div className="text-xs font-medium text-(--theme-foreground) mb-1">{title}</div>
        {subtitle && <div className="text-[11px] text-(--theme-muted) mb-3">{subtitle}</div>}
        {children}
    </div>
);

const KpiCard: React.FC<{ label: string; value: string; subtitle?: string }> = ({ label, value, subtitle }) => (
    <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl px-5 py-4">
        <div className="text-xs text-(--theme-muted) uppercase tracking-wide">{label}</div>
        <div className="text-2xl font-semibold text-(--theme-foreground) mt-1">{value}</div>
        {subtitle && <div className="text-xs text-(--theme-muted) mt-1">{subtitle}</div>}
    </div>
);
