import React, { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
    Area,
    AreaChart,
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
import {
    sessionsAnalyticsApi,
    type SessionAggregateResponse,
    type SessionAnalyticsBucket,
} from '../api/sessionsAnalyticsApi';

const formatDay = (iso: string): string => {
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return iso;
    return `${d.getUTCMonth() + 1}/${d.getUTCDate()}`;
};

const formatDuration = (seconds: number): string => {
    if (!Number.isFinite(seconds) || seconds <= 0) return '0s';
    if (seconds < 60) return `${seconds.toFixed(1)}s`;
    if (seconds < 3600) {
        const m = Math.floor(seconds / 60);
        const s = Math.round(seconds % 60);
        return s === 0 ? `${m}m` : `${m}m ${s}s`;
    }
    const h = Math.floor(seconds / 3600);
    const m = Math.round((seconds % 3600) / 60);
    return m === 0 ? `${h}h` : `${h}h ${m}m`;
};

interface Row {
    bucket: string;
    sessions: number;
    p50: number;
    p95: number;
    avgRuns: number;
}

const toRows = (buckets: SessionAnalyticsBucket[]): Row[] =>
    buckets.map(b => ({
        bucket: formatDay(b.day),
        sessions: b.sessionCount,
        p50: Math.round(b.p50DurationSeconds * 100) / 100,
        p95: Math.round(b.p95DurationSeconds * 100) / 100,
        avgRuns: Math.round(b.avgRunsPerSession * 100) / 100,
    }));

interface SessionAnalyticsTabProps {
    initialWindow?: number;
}

export const SessionAnalyticsTab: React.FC<SessionAnalyticsTabProps> = ({ initialWindow = 30 }) => {
    const [windowDays, setWindowDays] = useState<number>(initialWindow);

    const { data, isLoading, isFetching, error, refetch } = useQuery({
        queryKey: ['observability', 'sessions-aggregate', windowDays],
        queryFn: () => sessionsAnalyticsApi.get(windowDays),
        staleTime: 60_000,
    });

    const rows = useMemo(() => (data ? toRows(data.buckets) : []), [data]);

    const totals = useMemo(() => {
        if (!data || data.buckets.length === 0) {
            return { sessions: 0, weightedAvgRuns: 0, weightedP50: 0, weightedP95: 0 };
        }
        let sessions = 0;
        let weightedRuns = 0;
        let weightedP50 = 0;
        let weightedP95 = 0;
        for (const b of data.buckets) {
            sessions += b.sessionCount;
            weightedRuns += b.sessionCount * b.avgRunsPerSession;
            weightedP50 += b.sessionCount * b.p50DurationSeconds;
            weightedP95 += b.sessionCount * b.p95DurationSeconds;
        }
        return {
            sessions,
            weightedAvgRuns: sessions > 0 ? weightedRuns / sessions : 0,
            weightedP50: sessions > 0 ? weightedP50 / sessions : 0,
            weightedP95: sessions > 0 ? weightedP95 / sessions : 0,
        };
    }, [data]);

    if (error) {
        return (
            <Alert severity="error" title="Failed to load session analytics">
                {(error as Error).message}
            </Alert>
        );
    }

    return (
        <div className="space-y-4">
            <div className="flex items-center justify-between">
                <div className="flex items-center gap-3 text-xs">
                    <label className="text-(--theme-muted)">
                        Window
                        <select
                            className="ml-2 select select-xs bg-obsidian-base border-obsidian-stroke"
                            value={windowDays}
                            onChange={(e) => setWindowDays(Number(e.target.value))}
                        >
                            <option value={1}>Last 1 day</option>
                            <option value={7}>Last 7 days</option>
                            <option value={30}>Last 30 days</option>
                            <option value={90}>Last 90 days</option>
                        </select>
                    </label>
                    <span className="text-(--theme-muted)">
                        {isLoading ? 'Loading…' : `${totals.sessions.toLocaleString()} sessions`}
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
            ) : data && rows.length === 0 ? (
                <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl p-8 text-center text-(--theme-muted)">
                    No sessions in the selected window.
                </div>
            ) : data ? (
                <>
                    <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
                        <KpiCard
                            label="Avg runs / session"
                            value={totals.weightedAvgRuns.toFixed(2)}
                            subtitle={`${totals.sessions.toLocaleString()} sessions`}
                        />
                        <KpiCard
                            label="p50 duration"
                            value={formatDuration(totals.weightedP50)}
                            subtitle="weighted by session count"
                        />
                        <KpiCard
                            label="p95 duration"
                            value={formatDuration(totals.weightedP95)}
                            subtitle="weighted by session count"
                        />
                    </div>

                    <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
                        <Card title="Sessions per day">
                            <ResponsiveContainer width="100%" height={280}>
                                <AreaChart data={rows}>
                                    <CartesianGrid strokeDasharray="3 3" stroke="rgba(148,163,184,0.15)" />
                                    <XAxis dataKey="bucket" tick={{ fontSize: 11, fill: 'rgba(148,163,184,0.8)' }} />
                                    <YAxis tick={{ fontSize: 11, fill: 'rgba(148,163,184,0.8)' }} />
                                    <Tooltip />
                                    <Area
                                        type="monotone"
                                        dataKey="sessions"
                                        stroke="#3b82f6"
                                        fill="#3b82f6"
                                        fillOpacity={0.3}
                                    />
                                </AreaChart>
                            </ResponsiveContainer>
                        </Card>

                        <Card title="Session duration (p50 / p95)">
                            <ResponsiveContainer width="100%" height={280}>
                                <LineChart data={rows}>
                                    <CartesianGrid strokeDasharray="3 3" stroke="rgba(148,163,184,0.15)" />
                                    <XAxis dataKey="bucket" tick={{ fontSize: 11, fill: 'rgba(148,163,184,0.8)' }} />
                                    <YAxis
                                        tick={{ fontSize: 11, fill: 'rgba(148,163,184,0.8)' }}
                                        tickFormatter={(v: number) => formatDuration(v)}
                                    />
                                    <Tooltip formatter={(value) => formatDuration(Number(value))} />
                                    <Legend wrapperStyle={{ fontSize: 11 }} />
                                    <Line type="monotone" dataKey="p50" stroke="#10b981" dot={false} strokeWidth={2} />
                                    <Line type="monotone" dataKey="p95" stroke="#ef4444" dot={false} strokeWidth={2} />
                                </LineChart>
                            </ResponsiveContainer>
                        </Card>
                    </div>
                </>
            ) : null}
        </div>
    );
};

const Card: React.FC<{ title: string; children: React.ReactNode }> = ({ title, children }) => (
    <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl px-5 py-4">
        <div className="text-xs font-medium text-(--theme-foreground) mb-2">{title}</div>
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

export type { SessionAggregateResponse };
