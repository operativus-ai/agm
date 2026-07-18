import React, { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import {
    Bar,
    BarChart,
    CartesianGrid,
    Cell,
    Legend,
    Pie,
    PieChart,
    ResponsiveContainer,
    Tooltip,
    XAxis,
    YAxis,
} from 'recharts';
import { LuChevronLeft, LuChevronRight, LuRefreshCw } from 'react-icons/lu';
import { Alert } from '../../../shared/components/ui/Alert';
import { Button } from '../../../shared/components/ui/Button';
import {
    orchestrationAnalyticsApi,
    type Granularity,
    type OrchestrationAggregateResponse,
    type OrchestrationDecisionRow,
    type TimeBucket,
} from '../api/orchestrationAnalyticsApi';

const STRATEGY_PALETTE = [
    '#3b82f6',
    '#10b981',
    '#f59e0b',
    '#ef4444',
    '#8b5cf6',
    '#ec4899',
    '#06b6d4',
    '#84cc16',
];

const colorFor = (strategy: string, allStrategies: string[]): string => {
    const idx = allStrategies.indexOf(strategy);
    return STRATEGY_PALETTE[(idx >= 0 ? idx : 0) % STRATEGY_PALETTE.length];
};

const formatBucket = (iso: string, granularity: Granularity): string => {
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return iso;
    if (granularity === 'HOUR') {
        return `${d.getMonth() + 1}/${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:00`;
    }
    return `${d.getMonth() + 1}/${d.getDate()}`;
};

const flattenForBarChart = (
    overTime: TimeBucket[],
    strategies: string[],
    granularity: Granularity,
): Array<Record<string, number | string>> =>
    overTime.map(b => {
        const row: Record<string, number | string> = { bucket: formatBucket(b.bucket, granularity) };
        for (const s of strategies) row[s] = b.perStrategy[s] ?? 0;
        return row;
    });

interface OrchestrationAnalyticsTabProps {
    initialWindow?: number;
    initialGranularity?: Granularity;
}

export const OrchestrationAnalyticsTab: React.FC<OrchestrationAnalyticsTabProps> = ({
    initialWindow = 30,
    initialGranularity = 'DAY',
}) => {
    const [windowDays, setWindowDays] = useState<number>(initialWindow);
    const [granularity, setGranularity] = useState<Granularity>(initialGranularity);

    const { data, isLoading, isFetching, error, refetch } = useQuery({
        queryKey: ['observability', 'orchestration-aggregate', windowDays, granularity],
        queryFn: () => orchestrationAnalyticsApi.get(windowDays, granularity),
        staleTime: 60_000,
    });

    const strategies = useMemo(() => {
        if (!data) return [];
        const set = new Set<string>();
        for (const d of data.distribution) if (d.strategy) set.add(d.strategy);
        for (const b of data.overTime) for (const s of Object.keys(b.perStrategy)) set.add(s);
        return Array.from(set);
    }, [data]);

    const barData = useMemo(
        () => (data ? flattenForBarChart(data.overTime, strategies, granularity) : []),
        [data, strategies, granularity],
    );

    const total = data?.distribution.reduce((sum, d) => sum + d.count, 0) ?? 0;

    if (error) {
        return (
            <Alert severity="error" title="Failed to load orchestration analytics">
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
                        {isLoading ? 'Loading…' : `${total.toLocaleString()} decisions`}
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
            ) : data && data.distribution.length === 0 && data.overTime.length === 0 ? (
                <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl p-8 text-center text-(--theme-muted)">
                    No orchestration decisions in the selected window.
                </div>
            ) : data ? (
                <>
                <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
                    <Card title="Strategy distribution">
                        <ResponsiveContainer width="100%" height={280}>
                            <PieChart>
                                <Pie
                                    data={data.distribution}
                                    dataKey="count"
                                    nameKey="strategy"
                                    cx="50%"
                                    cy="50%"
                                    outerRadius={90}
                                    label={(props) => {
                                        const p = props as unknown as { strategy?: string; count?: number };
                                        if (!p.strategy) return '';
                                        return `${p.strategy}: ${p.count ?? 0}`;
                                    }}
                                >
                                    {data.distribution.map(d => (
                                        <Cell key={d.strategy} fill={colorFor(d.strategy, strategies)} />
                                    ))}
                                </Pie>
                                <Tooltip />
                            </PieChart>
                        </ResponsiveContainer>
                    </Card>

                    <Card title="Decisions over time">
                        <ResponsiveContainer width="100%" height={280}>
                            <BarChart data={barData}>
                                <CartesianGrid strokeDasharray="3 3" stroke="rgba(148,163,184,0.15)" />
                                <XAxis dataKey="bucket" tick={{ fontSize: 11, fill: 'rgba(148,163,184,0.8)' }} />
                                <YAxis tick={{ fontSize: 11, fill: 'rgba(148,163,184,0.8)' }} />
                                <Tooltip />
                                <Legend wrapperStyle={{ fontSize: 11 }} />
                                {strategies.map(s => (
                                    <Bar key={s} dataKey={s} stackId="strategy" fill={colorFor(s, strategies)} />
                                ))}
                            </BarChart>
                        </ResponsiveContainer>
                    </Card>
                </div>
                <DecisionDrillDown strategies={strategies} />
                </>
            ) : null}
        </div>
    );
};

interface DecisionDrillDownProps {
    strategies: string[];
}

const DECISION_PAGE_SIZE = 20;

const DecisionDrillDown: React.FC<DecisionDrillDownProps> = ({ strategies }) => {
    const [strategy, setStrategy] = useState<string>(strategies[0] ?? '');
    const [page, setPage] = useState<number>(0);

    // Reset page on strategy change so the new query starts from the top.
    useEffect(() => {
        setPage(0);
    }, [strategy]);

    // If the strategies list comes in late, lock onto the first available value.
    useEffect(() => {
        if (!strategy && strategies.length > 0) setStrategy(strategies[0]);
    }, [strategies, strategy]);

    const { data, isLoading, isFetching, error } = useQuery({
        queryKey: ['observability', 'orchestration-decisions', strategy, page, DECISION_PAGE_SIZE],
        queryFn: () => orchestrationAnalyticsApi.listDecisions(strategy, page, DECISION_PAGE_SIZE),
        enabled: Boolean(strategy),
        staleTime: 30_000,
    });

    if (strategies.length === 0) return null;

    return (
        <Card title="Decision drill-down">
            <div className="flex items-center gap-3 text-xs mb-3 flex-wrap">
                <label className="text-(--theme-muted)">
                    Strategy
                    <select
                        className="ml-2 select select-xs bg-obsidian-base border-obsidian-stroke"
                        value={strategy}
                        onChange={(e) => setStrategy(e.target.value)}
                    >
                        {strategies.map(s => (
                            <option key={s} value={s}>{s}</option>
                        ))}
                    </select>
                </label>
                {data && (
                    <span className="text-(--theme-muted)">
                        {data.page.totalElements.toLocaleString()} decision{data.page.totalElements === 1 ? '' : 's'}
                    </span>
                )}
                {isFetching && <span className="loading loading-spinner loading-xs" />}
            </div>

            {error ? (
                <Alert severity="error" title="Failed to load decisions">
                    {(error as Error).message}
                </Alert>
            ) : isLoading && !data ? (
                <div className="space-y-2">
                    {[1, 2, 3, 4].map(i => (
                        <div key={i} className="h-7 bg-obsidian-elevated/50 rounded animate-pulse" />
                    ))}
                </div>
            ) : data && data.content.length === 0 ? (
                <div className="text-(--theme-muted) text-sm py-6 text-center">
                    No decisions for {strategy} yet.
                </div>
            ) : data ? (
                <>
                    <DecisionTable rows={data.content} />
                    <div className="flex items-center justify-between mt-3 text-xs text-(--theme-muted)">
                        <span>
                            Page {data.page.number + 1} of {Math.max(data.page.totalPages, 1)}
                        </span>
                        <div className="flex items-center gap-2">
                            <Button
                                variant="ghost"
                                size="sm"
                                onClick={() => setPage(p => Math.max(0, p - 1))}
                                disabled={data.page.number === 0 || isFetching}
                                className="gap-1"
                            >
                                <LuChevronLeft className="w-4 h-4" />
                                Prev
                            </Button>
                            <Button
                                variant="ghost"
                                size="sm"
                                onClick={() => setPage(p => p + 1)}
                                disabled={data.page.number + 1 >= data.page.totalPages || isFetching}
                                className="gap-1"
                            >
                                Next
                                <LuChevronRight className="w-4 h-4" />
                            </Button>
                        </div>
                    </div>
                </>
            ) : null}
        </Card>
    );
};

const formatTimestamp = (iso: string): string => {
    const d = new Date(iso);
    return Number.isNaN(d.getTime()) ? '—' : d.toLocaleString();
};

const truncate = (s: string | null | undefined, max: number): string => {
    if (!s) return '';
    return s.length <= max ? s : `${s.slice(0, max - 1)}…`;
};

const DecisionTable: React.FC<{ rows: OrchestrationDecisionRow[] }> = ({ rows }) => (
    <table className="w-full text-sm">
        <thead>
            <tr className="border-b border-(--theme-muted)/10 text-(--theme-muted) text-xs">
                <th className="px-3 py-2 text-left font-medium">When</th>
                <th className="px-3 py-2 text-left font-medium">Run</th>
                <th className="px-3 py-2 text-left font-medium">Selected agent</th>
                <th className="px-3 py-2 text-left font-medium">Rationale</th>
            </tr>
        </thead>
        <tbody>
            {rows.map(r => (
                <tr key={r.id} className="border-b border-(--theme-muted)/5 last:border-b-0">
                    <td className="px-3 py-2 text-xs text-(--theme-muted) whitespace-nowrap">
                        {formatTimestamp(r.createdAt)}
                    </td>
                    <td className="px-3 py-2 text-xs">
                        <Link to={`/runs/${r.runId}`} className="font-mono hover:underline">
                            {truncate(r.runId, 28)}
                        </Link>
                    </td>
                    <td className="px-3 py-2 text-xs">
                        {r.selectedAgentId
                            ? <Link to={`/agents/${r.selectedAgentId}`} className="font-mono hover:underline">{r.selectedAgentId}</Link>
                            : <span className="text-(--theme-muted)">—</span>}
                    </td>
                    <td className="px-3 py-2 text-xs text-(--theme-muted)">
                        {r.rationale
                            ? <span title={r.rationale} className="max-w-[420px] inline-block truncate align-middle">{truncate(r.rationale, 120)}</span>
                            : <span className="text-(--theme-muted)">—</span>}
                    </td>
                </tr>
            ))}
        </tbody>
    </table>
);

const Card: React.FC<{ title: string; children: React.ReactNode }> = ({ title, children }) => (
    <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl px-5 py-4">
        <div className="text-xs font-medium text-(--theme-foreground) mb-2">{title}</div>
        {children}
    </div>
);

// Force named export to avoid unused warning when chart libraries tree-shake oddly.
export type { OrchestrationAggregateResponse };
