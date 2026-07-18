import React, { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { LuRefreshCw } from 'react-icons/lu';
import { Alert } from '../../../shared/components/ui/Alert';
import { Button } from '../../../shared/components/ui/Button';
import {
    delegationTopologyApi,
    type DelegationEdge,
    type DelegationTopologyResponse,
} from '../api/delegationTopologyApi';

interface CellSummary {
    total: number;
    perStrategy: Map<string, number>;
}

interface Axes {
    fromAgents: string[];
    toAgents: string[];
    cellByKey: Map<string, CellSummary>;
    maxCount: number;
}

const buildAxes = (edges: DelegationEdge[], strategyFilter: Set<string> | null): Axes => {
    const fromSet = new Set<string>();
    const toSet = new Set<string>();
    const cellByKey = new Map<string, CellSummary>();
    let maxCount = 0;

    for (const e of edges) {
        if (strategyFilter && !strategyFilter.has(e.strategy)) continue;
        fromSet.add(e.from);
        toSet.add(e.to);
        const key = `${e.from}|${e.to}`;
        let cell = cellByKey.get(key);
        if (!cell) {
            cell = { total: 0, perStrategy: new Map() };
            cellByKey.set(key, cell);
        }
        cell.total += e.count;
        cell.perStrategy.set(e.strategy, (cell.perStrategy.get(e.strategy) ?? 0) + e.count);
        if (cell.total > maxCount) maxCount = cell.total;
    }

    const fromAgents = Array.from(fromSet).sort();
    const toAgents = Array.from(toSet).sort();
    return { fromAgents, toAgents, cellByKey, maxCount };
};

const cellColor = (count: number, maxCount: number): string => {
    if (count <= 0) return 'transparent';
    // log scale → 0..1 intensity; map to green gradient.
    const ratio = Math.log(count + 1) / Math.log(maxCount + 1);
    const lightness = Math.round(85 - ratio * 50); // 85% → 35%
    return `hsl(142 70% ${lightness}%)`;
};

const formatStrategyBreakdown = (cell: CellSummary): string => {
    const parts: string[] = [];
    for (const [s, n] of cell.perStrategy) parts.push(`${s}: ${n}`);
    return parts.join(', ');
};

export const DelegationTopologyTab: React.FC = () => {
    const [windowDays, setWindowDays] = useState<number>(30);
    const [excludedStrategies, setExcludedStrategies] = useState<Set<string>>(new Set());

    const { data, isLoading, isFetching, error, refetch } = useQuery({
        queryKey: ['observability', 'delegation-topology', windowDays],
        queryFn: () => delegationTopologyApi.get(windowDays),
        staleTime: 60_000,
    });

    const allStrategies = useMemo(() => {
        if (!data) return [];
        const set = new Set<string>();
        for (const e of data.edges) set.add(e.strategy);
        return Array.from(set).sort();
    }, [data]);

    const strategyFilter = useMemo<Set<string> | null>(() => {
        if (excludedStrategies.size === 0) return null;
        return new Set(allStrategies.filter(s => !excludedStrategies.has(s)));
    }, [allStrategies, excludedStrategies]);

    const axes = useMemo<Axes>(
        () => (data ? buildAxes(data.edges, strategyFilter) : { fromAgents: [], toAgents: [], cellByKey: new Map(), maxCount: 0 }),
        [data, strategyFilter],
    );

    const toggleStrategy = (s: string) => {
        setExcludedStrategies(prev => {
            const next = new Set(prev);
            if (next.has(s)) next.delete(s); else next.add(s);
            return next;
        });
    };

    if (error) {
        return (
            <Alert severity="error" title="Failed to load delegation topology">
                {(error as Error).message}
            </Alert>
        );
    }

    return (
        <div className="space-y-4">
            <div className="flex items-center justify-between flex-wrap gap-3">
                <div className="flex items-center gap-3 text-xs flex-wrap">
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
                    {data && (
                        <span className="text-(--theme-muted)">
                            {data.nodes.length} agent{data.nodes.length === 1 ? '' : 's'} · {data.edges.length} edge{data.edges.length === 1 ? '' : 's'}
                        </span>
                    )}
                </div>
                <Button variant="outline" size="sm" onClick={() => refetch()} disabled={isFetching} className="gap-2">
                    {isFetching ? <span className="loading loading-spinner loading-sm" /> : <LuRefreshCw className="w-4 h-4" />}
                    Refresh
                </Button>
            </div>

            {allStrategies.length > 1 && (
                <div className="flex items-center gap-2 text-xs flex-wrap">
                    <span className="text-(--theme-muted)">Strategies:</span>
                    {allStrategies.map(s => {
                        const active = !excludedStrategies.has(s);
                        return (
                            <button
                                key={s}
                                type="button"
                                onClick={() => toggleStrategy(s)}
                                className={`px-2 py-0.5 rounded-full border text-xs font-mono transition-colors ${
                                    active
                                        ? 'bg-(--theme-card) border-(--theme-foreground)/30 text-(--theme-foreground)'
                                        : 'bg-transparent border-(--theme-muted)/20 text-(--theme-muted) line-through'
                                }`}
                            >
                                {s}
                            </button>
                        );
                    })}
                </div>
            )}

            {isLoading && !data ? (
                <div className="h-72 bg-obsidian-elevated/50 rounded-xl animate-pulse" />
            ) : data && axes.fromAgents.length === 0 ? (
                <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl p-8 text-center text-(--theme-muted)">
                    No delegation edges in the selected window.
                </div>
            ) : data ? (
                <Card title="Delegation matrix" subtitle="Rows: from-agent · Columns: to-agent · Cell color: log-scaled count (darker = more delegations)">
                    <Heatmap axes={axes} />
                </Card>
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

const Heatmap: React.FC<{ axes: Axes }> = ({ axes }) => (
    <div className="overflow-x-auto">
        <table className="text-xs border-separate" style={{ borderSpacing: '2px' }}>
            <thead>
                <tr>
                    <th className="text-left text-(--theme-muted) font-medium pr-3"></th>
                    {axes.toAgents.map(a => (
                        <th
                            key={a}
                            className="text-(--theme-muted) font-mono font-medium px-1 whitespace-nowrap text-left"
                            style={{ writingMode: 'vertical-rl', transform: 'rotate(180deg)', minWidth: '1.5rem' }}
                            title={a}
                        >
                            {a}
                        </th>
                    ))}
                </tr>
            </thead>
            <tbody>
                {axes.fromAgents.map(from => (
                    <tr key={from}>
                        <td
                            className="text-(--theme-muted) font-mono font-medium pr-3 max-w-[180px] truncate"
                            title={from}
                        >
                            {from}
                        </td>
                        {axes.toAgents.map(to => {
                            const cell = axes.cellByKey.get(`${from}|${to}`);
                            if (!cell) {
                                return <td key={to} className="w-6 h-6" />;
                            }
                            return (
                                <td key={to} className="w-6 h-6">
                                    <div
                                        className="mx-auto rounded-sm flex items-center justify-center text-[10px] font-medium text-(--theme-foreground)"
                                        title={`${from} → ${to}\nTotal: ${cell.total}\n${formatStrategyBreakdown(cell)}`}
                                        style={{
                                            width: 24,
                                            height: 24,
                                            background: cellColor(cell.total, axes.maxCount),
                                        }}
                                    >
                                        {cell.total > 0 ? cell.total : ''}
                                    </div>
                                </td>
                            );
                        })}
                    </tr>
                ))}
            </tbody>
        </table>
    </div>
);

export type { DelegationTopologyResponse };
