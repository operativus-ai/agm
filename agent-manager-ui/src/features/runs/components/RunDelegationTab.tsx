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
import { Badge } from '../../../shared/components/ui/Badge';
import { runsApi } from '../api/runsApi';
import type { OrchestrationDecision, RunCostNode } from '../types/runs';

const COST_TREE_MAX_DEPTH = 10;

const truncate = (s: string | null | undefined, max = 80): string => {
    if (!s) return '';
    if (s.length <= max) return s;
    return `${s.slice(0, max - 1)}…`;
};

interface RunDelegationTabProps {
    runId: string;
}

export const RunDelegationTab: React.FC<RunDelegationTabProps> = ({ runId }) => {
    const treeQuery = useQuery({
        queryKey: ['runs', 'cost-tree', runId, COST_TREE_MAX_DEPTH],
        queryFn: () => runsApi.getCostTree(runId, COST_TREE_MAX_DEPTH),
        staleTime: 60_000,
        retry: (failureCount, err) => {
            if ((err as { status?: number }).status === 404) return false;
            return failureCount < 2;
        },
    });

    const decisionsQuery = useQuery({
        queryKey: ['runs', 'orchestration-decisions', runId],
        queryFn: () => runsApi.getOrchestrationDecisions(runId),
        staleTime: 60_000,
        retry: (failureCount, err) => {
            if ((err as { status?: number }).status === 404) return false;
            return failureCount < 2;
        },
    });

    const decisionByAgent = useMemo<Map<string, OrchestrationDecision>>(() => {
        const map = new Map<string, OrchestrationDecision>();
        if (!decisionsQuery.data) return map;
        for (const d of decisionsQuery.data) {
            if (d.selectedAgentId && !map.has(d.selectedAgentId)) {
                map.set(d.selectedAgentId, d);
            }
        }
        return map;
    }, [decisionsQuery.data]);

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
            accessorKey: 'agentId',
            header: 'Agent',
            cell: ({ row, getValue }) => {
                const v = getValue() as string | null;
                if (!v) return <span className="text-(--theme-muted) text-xs">—</span>;
                const isRoot = row.depth === 0;
                return (
                    <Link
                        to={`/agents/${v}`}
                        className={`font-mono text-xs hover:underline ${isRoot ? 'font-semibold text-(--theme-foreground)' : ''}`}
                    >
                        {v}
                    </Link>
                );
            },
        },
        {
            accessorKey: 'id',
            header: 'Run',
            cell: ({ getValue }) => (
                <Link to={`/runs/${getValue() as string}`} className="font-mono text-xs hover:underline">
                    {truncate(getValue() as string, 32)}
                </Link>
            ),
        },
        {
            accessorKey: 'depth',
            header: 'Depth',
            cell: ({ getValue }) => <span className="text-xs">{(getValue() as number | null) ?? '—'}</span>,
        },
        {
            id: 'strategy',
            header: 'Strategy',
            cell: ({ row }) => {
                const agentId = row.original.agentId;
                const decision = agentId ? decisionByAgent.get(agentId) : undefined;
                if (!decision?.strategy) return <span className="text-(--theme-muted) text-xs">—</span>;
                return <Badge variant="ghost" className="text-[10px] font-mono">{decision.strategy}</Badge>;
            },
        },
        {
            id: 'rationale',
            header: 'Rationale',
            cell: ({ row }) => {
                const agentId = row.original.agentId;
                const decision = agentId ? decisionByAgent.get(agentId) : undefined;
                const r = decision?.rationale;
                if (!r) return <span className="text-(--theme-muted) text-xs">—</span>;
                return (
                    <span
                        title={r}
                        className="text-xs text-(--theme-muted) max-w-[420px] inline-block truncate"
                    >
                        {truncate(r, 120)}
                    </span>
                );
            },
        },
    ], [decisionByAgent]);

    const treeData = useMemo<RunCostNode[]>(() => (treeQuery.data ? [treeQuery.data] : []), [treeQuery.data]);

    const table = useReactTable({
        data: treeData,
        columns,
        state: { expanded },
        onExpandedChange: setExpanded,
        getSubRows: (row) => (row.subRuns && row.subRuns.length > 0 ? row.subRuns : undefined),
        getCoreRowModel: getCoreRowModel(),
        getExpandedRowModel: getExpandedRowModel(),
    });

    const treeUnavailable = treeQuery.isError && (treeQuery.error as { status?: number }).status === 404;

    if (treeQuery.isLoading) {
        return (
            <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl p-4 space-y-2">
                {[1, 2, 3].map(i => (
                    <div key={i} className="h-8 bg-obsidian-elevated/50 rounded animate-pulse" />
                ))}
            </div>
        );
    }

    if (treeUnavailable) {
        return (
            <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl p-8 text-center text-(--theme-muted)">
                Delegation tree not yet available for this run.
            </div>
        );
    }

    if (treeQuery.isError) {
        return (
            <Alert severity="error" title="Failed to load delegation tree">
                {(treeQuery.error as Error).message}
            </Alert>
        );
    }

    const root = treeQuery.data;
    if (!root || !root.subRuns || root.subRuns.length === 0) {
        return (
            <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl p-8 text-center text-(--theme-muted)">
                This run has no child delegations.
            </div>
        );
    }

    return (
        <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl overflow-hidden">
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
            </table>
        </div>
    );
};
