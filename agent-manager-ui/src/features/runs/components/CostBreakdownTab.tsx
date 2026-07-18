import React, { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import {
    type ColumnDef,
    type ExpandedState,
    flexRender,
    getCoreRowModel,
    getExpandedRowModel,
    useReactTable,
} from '@tanstack/react-table';
import { LuChevronDown, LuChevronRight } from 'react-icons/lu';
import { Alert } from '../../../shared/components/ui/Alert';
import { runsApi } from '../api/runsApi';
import type { RunCostNode } from '../types/runs';

const COST_TREE_MAX_DEPTH = 10;

const formatNum = (v: number | null | undefined): string =>
    (v == null ? '—' : v.toLocaleString());

const formatCost = (v: string | number | null | undefined): string => {
    if (v === null || v === undefined) return '—';
    const n = typeof v === 'string' ? Number(v) : v;
    return Number.isFinite(n) ? `$${n.toFixed(4)}` : '—';
};

interface CostBreakdownTabProps {
    runId: string;
}

export const CostBreakdownTab: React.FC<CostBreakdownTabProps> = ({ runId }) => {
    const treeCostQuery = useQuery({
        queryKey: ['runs', 'tree-cost', runId],
        queryFn: () => runsApi.getTreeCost(runId),
        staleTime: 60_000,
        retry: (failureCount, err) => {
            if ((err as { status?: number }).status === 404) return false;
            return failureCount < 2;
        },
    });

    const costTreeQuery = useQuery({
        queryKey: ['runs', 'cost-tree', runId, COST_TREE_MAX_DEPTH],
        queryFn: () => runsApi.getCostTree(runId, COST_TREE_MAX_DEPTH),
        staleTime: 60_000,
        retry: (failureCount, err) => {
            if ((err as { status?: number }).status === 404) return false;
            return failureCount < 2;
        },
    });

    const [expanded, setExpanded] = React.useState<ExpandedState>({});

    const columns = useMemo<ColumnDef<RunCostNode>[]>(() => [
        {
            id: 'expand',
            header: '',
            cell: ({ row }) => {
                if (!row.getCanExpand()) {
                    return <span className="inline-block w-3.5" style={{ marginLeft: row.depth * 16 }} />;
                }
                const Chevron = row.getIsExpanded() ? LuChevronDown : LuChevronRight;
                return (
                    <button
                        type="button"
                        onClick={row.getToggleExpandedHandler()}
                        className="text-(--theme-muted) hover:text-(--theme-foreground)"
                        style={{ marginLeft: row.depth * 16 }}
                    >
                        <Chevron className="w-3.5 h-3.5" />
                    </button>
                );
            },
        },
        {
            accessorKey: 'id',
            header: 'Run',
            cell: ({ getValue }) => (
                <Link to={`/runs/${getValue() as string}`} className="font-mono text-xs hover:underline">
                    {getValue() as string}
                </Link>
            ),
        },
        {
            accessorKey: 'agentId',
            header: 'Agent',
            cell: ({ getValue }) => {
                const v = getValue() as string | null;
                return v
                    ? <Link to={`/agents/${v}`} className="font-mono text-xs hover:underline">{v}</Link>
                    : <span className="text-(--theme-muted) text-xs">—</span>;
            },
        },
        {
            accessorKey: 'depth',
            header: 'Depth',
            cell: ({ getValue }) => <span className="text-xs">{(getValue() as number | null) ?? '—'}</span>,
        },
        {
            accessorKey: 'inputTokens',
            header: 'In',
            cell: ({ getValue }) => (
                <span className="text-xs text-(--theme-muted) whitespace-nowrap">
                    {formatNum(getValue() as number | null)}
                </span>
            ),
        },
        {
            accessorKey: 'outputTokens',
            header: 'Out',
            cell: ({ getValue }) => (
                <span className="text-xs text-(--theme-muted) whitespace-nowrap">
                    {formatNum(getValue() as number | null)}
                </span>
            ),
        },
        {
            accessorKey: 'totalCostUsd',
            header: 'Cost',
            cell: ({ getValue }) => (
                <span className="text-xs whitespace-nowrap">
                    {formatCost(getValue() as RunCostNode['totalCostUsd'])}
                </span>
            ),
        },
    ], []);

    const treeData = useMemo<RunCostNode[]>(() => {
        return costTreeQuery.data ? [costTreeQuery.data] : [];
    }, [costTreeQuery.data]);

    const table = useReactTable({
        data: treeData,
        columns,
        state: { expanded },
        onExpandedChange: setExpanded,
        getSubRows: (row) => (row.subRuns && row.subRuns.length > 0 ? row.subRuns : undefined),
        getCoreRowModel: getCoreRowModel(),
        getExpandedRowModel: getExpandedRowModel(),
    });

    const treeCost = treeCostQuery.data;
    const treeCostUnavailable =
        treeCostQuery.isError && (treeCostQuery.error as { status?: number }).status === 404;
    const costTreeUnavailable =
        costTreeQuery.isError && (costTreeQuery.error as { status?: number }).status === 404;

    if (treeCostUnavailable && costTreeUnavailable) {
        return (
            <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl p-8 text-center text-(--theme-muted)">
                Cost data not yet available.
            </div>
        );
    }

    return (
        <div className="space-y-4">
            <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl px-5 py-4">
                <div className="text-xs font-medium text-(--theme-foreground) mb-2">Tree rollup</div>
                {treeCostQuery.isLoading ? (
                    <div className="h-6 bg-obsidian-elevated/50 rounded animate-pulse w-32" />
                ) : treeCostUnavailable ? (
                    <div className="text-sm text-(--theme-muted)">No rollup data — this run has no recorded cost yet.</div>
                ) : treeCost ? (
                    <div className="flex items-baseline gap-6">
                        <div>
                            <div className="text-[11px] uppercase tracking-wider text-(--theme-muted)">Total cost</div>
                            <div className="text-2xl font-semibold text-(--theme-foreground)">
                                {formatCost(treeCost.treeTotalCostUsd)}
                            </div>
                        </div>
                        <div>
                            <div className="text-[11px] uppercase tracking-wider text-(--theme-muted)">Runs in tree</div>
                            <div className="text-2xl font-semibold text-(--theme-foreground)">
                                {treeCost.runCount.toLocaleString()}
                            </div>
                        </div>
                        <div>
                            <div className="text-[11px] uppercase tracking-wider text-(--theme-muted)">Root run</div>
                            <Link
                                to={`/runs/${treeCost.rootRunId}`}
                                className="font-mono text-sm hover:underline"
                            >
                                {treeCost.rootRunId}
                            </Link>
                        </div>
                    </div>
                ) : null}
            </div>

            {costTreeQuery.isError && !costTreeUnavailable && (
                <Alert severity="error" title="Failed to load cost tree">
                    {(costTreeQuery.error as Error).message}
                </Alert>
            )}

            <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl overflow-hidden">
                {costTreeQuery.isLoading ? (
                    <div className="p-4 space-y-2">
                        {[1, 2, 3].map(i => (
                            <div key={i} className="h-8 bg-obsidian-elevated/50 rounded animate-pulse" />
                        ))}
                    </div>
                ) : costTreeUnavailable ? (
                    <div className="p-8 text-center text-(--theme-muted)">
                        Cost tree not yet available for this run.
                    </div>
                ) : (
                    <table className="w-full text-sm">
                        <thead>
                            <tr className="border-b border-(--theme-muted)/10 text-(--theme-muted) text-xs">
                                {table.getHeaderGroups()[0].headers.map(h => (
                                    <th key={h.id} className="px-3 py-2 text-left font-medium">
                                        {h.isPlaceholder ? null : flexRender(h.column.columnDef.header, h.getContext())}
                                    </th>
                                ))}
                            </tr>
                        </thead>
                        <tbody>
                            {table.getRowModel().rows.map(row => (
                                <tr key={row.id} className="border-b border-(--theme-muted)/5 last:border-b-0">
                                    {row.getVisibleCells().map(cell => (
                                        <td key={cell.id} className="px-3 py-2 align-middle">
                                            {flexRender(cell.column.columnDef.cell, cell.getContext())}
                                        </td>
                                    ))}
                                </tr>
                            ))}
                        </tbody>
                        {treeCost && (
                            <tfoot>
                                <tr className="border-t border-(--theme-muted)/10 bg-(--theme-muted)/5 text-xs">
                                    <td colSpan={6} className="px-3 py-2 text-right text-(--theme-muted)">Total cost</td>
                                    <td className="px-3 py-2 font-semibold whitespace-nowrap">
                                        {formatCost(treeCost.treeTotalCostUsd)}
                                    </td>
                                </tr>
                            </tfoot>
                        )}
                    </table>
                )}
            </div>
        </div>
    );
};
