import React, { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import {
    Bar,
    BarChart,
    CartesianGrid,
    ResponsiveContainer,
    Tooltip,
    XAxis,
    YAxis,
} from 'recharts';
import { LuRefreshCw } from 'react-icons/lu';
import { Alert } from '../../../shared/components/ui/Alert';
import { Badge } from '../../../shared/components/ui/Badge';
import { Button } from '../../../shared/components/ui/Button';
import {
    safetyAnalyticsApi,
    type FlaggedRun,
    type SafetyAggregateResponse,
    type SafetyHeatmapCell,
} from '../api/safetyAnalyticsApi';

const HISTOGRAM_BUCKETS = 10;

const cellColor = (avgScore: number): string => {
    if (avgScore < 0.3) return '#10b981';
    if (avgScore < 0.7) return '#f59e0b';
    return '#ef4444';
};

const scoreVariant = (score: number): 'success' | 'warning' | 'error' => {
    if (score < 0.3) return 'success';
    if (score < 0.7) return 'warning';
    return 'error';
};

const formatDate = (iso: string): string => {
    const d = new Date(iso);
    return Number.isNaN(d.getTime()) ? '—' : `${d.getMonth() + 1}/${d.getDate()}`;
};

const formatTimestamp = (iso: string): string => {
    const d = new Date(iso);
    return Number.isNaN(d.getTime()) ? '—' : d.toLocaleString();
};

interface HistogramRow {
    bucket: string;
    count: number;
}

const buildHistogram = (cells: SafetyHeatmapCell[]): HistogramRow[] => {
    const buckets: number[] = new Array(HISTOGRAM_BUCKETS).fill(0);
    for (const c of cells) {
        // Weight by total runs in the cell — gives the histogram of avg-score
        // mass, not just per-cell count.
        const idx = Math.min(HISTOGRAM_BUCKETS - 1, Math.max(0, Math.floor(c.avgScore * HISTOGRAM_BUCKETS)));
        buckets[idx] += c.total;
    }
    return buckets.map((count, i) => ({
        bucket: `${(i / HISTOGRAM_BUCKETS).toFixed(1)}-${((i + 1) / HISTOGRAM_BUCKETS).toFixed(1)}`,
        count,
    }));
};

interface HeatmapAxes {
    agents: string[];
    days: string[];
    cellByKey: Map<string, SafetyHeatmapCell>;
}

const buildAxes = (cells: SafetyHeatmapCell[]): HeatmapAxes => {
    const agents = Array.from(new Set(cells.map(c => c.agentId))).sort();
    const days = Array.from(new Set(cells.map(c => c.day))).sort();
    const cellByKey = new Map<string, SafetyHeatmapCell>();
    for (const c of cells) cellByKey.set(`${c.agentId}|${c.day}`, c);
    return { agents, days, cellByKey };
};

export const SafetyAnalyticsTab: React.FC = () => {
    const [windowDays, setWindowDays] = useState<number>(30);

    const { data, isLoading, isFetching, error, refetch } = useQuery({
        queryKey: ['observability', 'safety-aggregate', windowDays],
        queryFn: () => safetyAnalyticsApi.get(windowDays),
        staleTime: 60_000,
    });

    const histogram = useMemo(() => (data ? buildHistogram(data.cells) : []), [data]);
    const axes = useMemo(() => (data ? buildAxes(data.cells) : { agents: [], days: [], cellByKey: new Map() }), [data]);

    if (error) {
        return (
            <Alert severity="error" title="Failed to load safety analytics">
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
                            <option value={7}>Last 7 days</option>
                            <option value={30}>Last 30 days</option>
                            <option value={90}>Last 90 days</option>
                        </select>
                    </label>
                    {data && (
                        <span className="text-(--theme-muted)">
                            {axes.agents.length} agent{axes.agents.length === 1 ? '' : 's'} · {data.flaggedRunsTopN.length} flagged
                        </span>
                    )}
                </div>
                <Button variant="outline" size="sm" onClick={() => refetch()} disabled={isFetching} className="gap-2">
                    {isFetching ? <span className="loading loading-spinner loading-sm" /> : <LuRefreshCw className="w-4 h-4" />}
                    Refresh
                </Button>
            </div>

            {isLoading && !data ? (
                <div className="space-y-4">
                    <div className="h-72 bg-obsidian-elevated/50 rounded-xl animate-pulse" />
                    <div className="h-48 bg-obsidian-elevated/50 rounded-xl animate-pulse" />
                </div>
            ) : data && data.cells.length === 0 ? (
                <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl p-8 text-center text-(--theme-muted)">
                    No safety-scored runs in the selected window.
                </div>
            ) : data ? (
                <>
                    <Card title="Per-agent / per-day average risk" subtitle="Cell color: green < 0.3, amber 0.3–0.7, red > 0.7. Cell size scales with run volume.">
                        <Heatmap axes={axes} />
                    </Card>

                    <Card title="Score distribution (10 buckets, 0.0–1.0)" subtitle="Weighted by run volume per cell.">
                        <ResponsiveContainer width="100%" height={200}>
                            <BarChart data={histogram}>
                                <CartesianGrid strokeDasharray="3 3" stroke="rgba(148,163,184,0.15)" />
                                <XAxis dataKey="bucket" tick={{ fontSize: 11, fill: 'rgba(148,163,184,0.8)' }} />
                                <YAxis tick={{ fontSize: 11, fill: 'rgba(148,163,184,0.8)' }} />
                                <Tooltip />
                                <Bar dataKey="count" fill="#8b5cf6" />
                            </BarChart>
                        </ResponsiveContainer>
                    </Card>

                    <Card title="Top flagged runs (top 20)">
                        <FlaggedRunsTable rows={data.flaggedRunsTopN} />
                    </Card>
                </>
            ) : null}
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

const Heatmap: React.FC<{ axes: HeatmapAxes }> = ({ axes }) => {
    if (axes.agents.length === 0 || axes.days.length === 0) return null;
    const maxTotal = Math.max(1, ...Array.from(axes.cellByKey.values()).map(c => c.total));

    return (
        <div className="overflow-x-auto">
            <table className="text-xs border-separate" style={{ borderSpacing: '2px' }}>
                <thead>
                    <tr>
                        <th className="text-left text-(--theme-muted) font-medium pr-2"></th>
                        {axes.days.map(d => (
                            <th key={d} className="text-(--theme-muted) font-mono font-medium px-1 whitespace-nowrap">
                                {formatDate(d)}
                            </th>
                        ))}
                    </tr>
                </thead>
                <tbody>
                    {axes.agents.map(agent => (
                        <tr key={agent}>
                            <td className="text-(--theme-muted) font-mono font-medium pr-3 max-w-[140px] truncate" title={agent}>
                                {agent}
                            </td>
                            {axes.days.map(day => {
                                const cell = axes.cellByKey.get(`${agent}|${day}`);
                                if (!cell) {
                                    return <td key={day} className="w-6 h-6" />;
                                }
                                const sizeRatio = Math.log(cell.total + 1) / Math.log(maxTotal + 1);
                                const sizePx = Math.max(8, Math.round(8 + sizeRatio * 16));
                                return (
                                    <td key={day} className="w-6 h-6">
                                        <div
                                            className="mx-auto rounded-sm"
                                            title={`${agent} · ${formatDate(day)}\nAvg: ${cell.avgScore.toFixed(3)} · Max: ${cell.maxScore.toFixed(3)}\nFlagged: ${cell.flagged} / ${cell.total}`}
                                            style={{
                                                width: sizePx,
                                                height: sizePx,
                                                background: cellColor(cell.avgScore),
                                            }}
                                        />
                                    </td>
                                );
                            })}
                        </tr>
                    ))}
                </tbody>
            </table>
        </div>
    );
};

const FlaggedRunsTable: React.FC<{ rows: FlaggedRun[] }> = ({ rows }) => {
    if (rows.length === 0) {
        return <div className="text-(--theme-muted) text-sm py-4 text-center">No flagged runs in the window.</div>;
    }
    return (
        <table className="w-full text-sm">
            <thead>
                <tr className="border-b border-(--theme-muted)/10 text-(--theme-muted) text-xs">
                    <th className="px-3 py-2 text-left font-medium">When</th>
                    <th className="px-3 py-2 text-left font-medium">Agent</th>
                    <th className="px-3 py-2 text-left font-medium">Run</th>
                    <th className="px-3 py-2 text-right font-medium">Risk</th>
                </tr>
            </thead>
            <tbody>
                {rows.map(r => (
                    <tr key={r.runId} className="border-b border-(--theme-muted)/5 last:border-b-0">
                        <td className="px-3 py-2 text-xs text-(--theme-muted) whitespace-nowrap">
                            {formatTimestamp(r.createdAt)}
                        </td>
                        <td className="px-3 py-2 text-xs">
                            {r.agentId
                                ? <Link to={`/agents/${r.agentId}`} className="font-mono hover:underline">{r.agentId}</Link>
                                : <span className="text-(--theme-muted)">—</span>}
                        </td>
                        <td className="px-3 py-2 text-xs">
                            <Link to={`/runs/${r.runId}`} className="font-mono hover:underline">{r.runId}</Link>
                        </td>
                        <td className="px-3 py-2 text-right">
                            <Badge variant={scoreVariant(r.score)} className="text-xs">
                                {r.score.toFixed(3)}
                            </Badge>
                        </td>
                    </tr>
                ))}
            </tbody>
        </table>
    );
};

export type { SafetyAggregateResponse };
