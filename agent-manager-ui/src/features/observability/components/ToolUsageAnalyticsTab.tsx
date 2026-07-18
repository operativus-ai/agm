import React, { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
    Bar,
    BarChart,
    CartesianGrid,
    Legend,
    ResponsiveContainer,
    Tooltip,
    XAxis,
    YAxis,
} from 'recharts';
import { LuRefreshCw } from 'react-icons/lu';
import { Alert } from '../../../shared/components/ui/Alert';
import { Badge } from '../../../shared/components/ui/Badge';
import { Button } from '../../../shared/components/ui/Button';
import { DataTable } from '../../../shared/components/ui/DataTable';
import type { ColumnDef } from '@tanstack/react-table';
import {
    toolAnalyticsApi,
    type Granularity,
    type ToolStat,
    type ToolTimeBucket,
} from '../api/toolAnalyticsApi';

const TOP_N_TOOLS = 6;

const PALETTE = [
    '#3b82f6',
    '#10b981',
    '#f59e0b',
    '#ef4444',
    '#8b5cf6',
    '#ec4899',
    '#06b6d4',
    '#84cc16',
];

const colorFor = (tool: string, all: string[]): string => {
    const idx = all.indexOf(tool);
    return PALETTE[(idx >= 0 ? idx : 0) % PALETTE.length];
};

const formatBucket = (iso: string, granularity: Granularity): string => {
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return iso;
    if (granularity === 'HOUR') {
        return `${d.getMonth() + 1}/${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:00`;
    }
    return `${d.getMonth() + 1}/${d.getDate()}`;
};

const formatDuration = (ms: number): string => {
    if (!Number.isFinite(ms)) return '—';
    if (ms < 1000) return `${Math.round(ms)}ms`;
    const s = ms / 1000;
    return s < 60 ? `${s.toFixed(1)}s` : `${(s / 60).toFixed(1)}m`;
};

const flattenForBarChart = (
    overTime: ToolTimeBucket[],
    tools: string[],
    granularity: Granularity,
): Array<Record<string, number | string>> =>
    overTime.map(b => {
        const row: Record<string, number | string> = { bucket: formatBucket(b.bucket, granularity) };
        for (const t of tools) row[t] = b.perTool[t] ?? 0;
        return row;
    });

export const ToolUsageAnalyticsTab: React.FC<{ initialWindow?: number; initialGranularity?: Granularity }> = ({
    initialWindow = 30,
    initialGranularity = 'DAY',
}) => {
    const [windowDays, setWindowDays] = useState<number>(initialWindow);
    const [granularity, setGranularity] = useState<Granularity>(initialGranularity);

    const { data, isLoading, isFetching, error, refetch } = useQuery({
        queryKey: ['observability', 'tool-aggregate', windowDays, granularity],
        queryFn: () => toolAnalyticsApi.get(windowDays, granularity),
        staleTime: 60_000,
    });

    const allTools = useMemo(() => data?.tools.map(t => t.toolName) ?? [], [data]);
    const topTools = useMemo(() => allTools.slice(0, TOP_N_TOOLS), [allTools]);

    const barData = useMemo(
        () => (data ? flattenForBarChart(data.overTime, topTools, granularity) : []),
        [data, topTools, granularity],
    );

    const toolColumns = useMemo<ColumnDef<ToolStat, unknown>[]>(() => [
        {
            accessorKey: 'toolName',
            header: 'Tool',
            cell: ({ row }) => (
                <span className="font-mono text-xs">
                    <span
                        className="inline-block w-2 h-2 rounded-full mr-2 align-middle"
                        style={{ background: colorFor(row.original.toolName, allTools) }}
                    />
                    {row.original.toolName}
                </span>
            ),
        },
        {
            accessorKey: 'totalCount',
            header: 'Invocations',
            cell: ({ getValue }) => <div className="text-right text-xs">{(getValue() as number).toLocaleString()}</div>,
        },
        {
            accessorKey: 'errorCount',
            header: 'Errors',
            cell: ({ getValue }) => <div className="text-right text-xs">{(getValue() as number).toLocaleString()}</div>,
        },
        {
            accessorKey: 'avgDurationMs',
            header: 'Avg duration',
            cell: ({ getValue }) => (
                <div className="text-right text-xs whitespace-nowrap">{formatDuration(getValue() as number)}</div>
            ),
        },
        {
            id: 'errorRate',
            header: 'Error rate',
            accessorFn: (row) => (row.totalCount > 0 ? (row.errorCount / row.totalCount) * 100 : 0),
            cell: ({ getValue }) => {
                const errorRate = getValue() as number;
                const errorVariant = errorRate >= 10 ? 'error' : errorRate >= 1 ? 'warning' : 'ghost';
                return (
                    <div className="text-right">
                        <Badge variant={errorVariant} className="text-xs">
                            {errorRate.toFixed(1)}%
                        </Badge>
                    </div>
                );
            },
        },
    ], [allTools]);

    const totalInvocations = data?.tools.reduce((sum, t) => sum + t.totalCount, 0) ?? 0;

    if (error) {
        return (
            <Alert severity="error" title="Failed to load tool analytics">
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
                    <label className="text-(--theme-muted)">
                        Granularity
                        <select
                            className="ml-2 select select-xs bg-obsidian-base border-obsidian-stroke"
                            value={granularity}
                            onChange={(e) => setGranularity(e.target.value as Granularity)}
                        >
                            <option value="HOUR">Hour</option>
                            <option value="DAY">Day</option>
                            <option value="WEEK">Week</option>
                            <option value="MONTH">Month</option>
                        </select>
                    </label>
                    <span className="text-(--theme-muted)">
                        {isLoading ? 'Loading…' : `${totalInvocations.toLocaleString()} invocations`}
                    </span>
                </div>
                <Button variant="outline" size="sm" onClick={() => refetch()} disabled={isFetching} className="gap-2">
                    {isFetching ? <span className="loading loading-spinner loading-sm" /> : <LuRefreshCw className="w-4 h-4" />}
                    Refresh
                </Button>
            </div>

            {isLoading && !data ? (
                <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
                    <div className="h-72 bg-obsidian-elevated/50 rounded-xl animate-pulse" />
                    <div className="h-72 bg-obsidian-elevated/50 rounded-xl animate-pulse" />
                </div>
            ) : data && data.tools.length === 0 ? (
                <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl p-8 text-center text-(--theme-muted)">
                    No tool invocations in the selected window.
                </div>
            ) : data ? (
                <>
                    <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
                        <Card title="Per-tool stats">
                            <DataTable columns={toolColumns} data={data.tools} enablePagination defaultPageSize={25} />
                        </Card>

                        <Card title={`Tool usage over time${allTools.length > TOP_N_TOOLS ? ` (top ${TOP_N_TOOLS})` : ''}`}>
                            <ResponsiveContainer width="100%" height={280}>
                                <BarChart data={barData}>
                                    <CartesianGrid strokeDasharray="3 3" stroke="rgba(148,163,184,0.15)" />
                                    <XAxis dataKey="bucket" tick={{ fontSize: 11, fill: 'rgba(148,163,184,0.8)' }} />
                                    <YAxis tick={{ fontSize: 11, fill: 'rgba(148,163,184,0.8)' }} />
                                    <Tooltip />
                                    <Legend wrapperStyle={{ fontSize: 11 }} />
                                    {topTools.map(t => (
                                        <Bar key={t} dataKey={t} stackId="tool" fill={colorFor(t, allTools)} />
                                    ))}
                                </BarChart>
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
