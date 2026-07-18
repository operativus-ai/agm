import React, { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import type { ColumnDef } from '@tanstack/react-table';
import { LuRefreshCw } from 'react-icons/lu';
import { Alert } from '../../../shared/components/ui/Alert';
import { Badge } from '../../../shared/components/ui/Badge';
import { Button } from '../../../shared/components/ui/Button';
import { DataTable } from '../../../shared/components/ui/DataTable';
import { runsApi } from '../../runs/api/runsApi';
import type { AgentRunResponse, RunStatus } from '../../runs/types/runs';

interface AgentRunsTabProps {
    agentId: string;
}

const PAGE_SIZE = 20;

const statusVariant = (s: RunStatus): 'success' | 'error' | 'warning' | 'info' | 'ghost' => {
    if (s === 'COMPLETED' || s === 'APPROVED') return 'success';
    if (s === 'FAILED' || s === 'REJECTED' || s === 'EXPIRED' || s === 'CANCELLED') return 'error';
    if (s === 'RUNNING' || s === 'PROCESSING') return 'info';
    if (s === 'PAUSED' || s === 'QUEUED' || s === 'PENDING') return 'warning';
    return 'ghost';
};

const formatTimestamp = (iso: string): string => {
    const d = new Date(iso);
    return Number.isNaN(d.getTime()) ? '—' : d.toLocaleString();
};

const formatDuration = (ms: number | null): string => {
    if (ms == null) return '—';
    if (ms < 1000) return `${ms}ms`;
    if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
    return `${Math.floor(ms / 60_000)}m ${Math.round((ms % 60_000) / 1000)}s`;
};

const formatCost = (v: string | number | null): string => {
    if (v == null) return '—';
    const n = typeof v === 'string' ? Number(v) : v;
    return Number.isFinite(n) ? `$${n.toFixed(4)}` : '—';
};

const RUN_COLUMNS: ColumnDef<AgentRunResponse, unknown>[] = [
    {
        accessorKey: 'status',
        header: 'Status',
        cell: ({ getValue }) => (
            <Badge variant={statusVariant(getValue() as RunStatus)} className="text-xs">
                {getValue() as string}
            </Badge>
        ),
    },
    {
        accessorKey: 'id',
        header: 'Run',
        enableSorting: false,
        cell: ({ getValue }) => (
            <Link to={`/runs/${getValue() as string}`} className="font-mono text-xs hover:underline">
                {getValue() as string}
            </Link>
        ),
    },
    {
        accessorKey: 'createdAt',
        header: 'Started',
        cell: ({ getValue }) => (
            <span className="text-xs text-(--theme-muted) whitespace-nowrap">{formatTimestamp(getValue() as string)}</span>
        ),
    },
    {
        accessorKey: 'durationMs',
        header: 'Duration',
        cell: ({ getValue }) => <div className="text-right whitespace-nowrap">{formatDuration(getValue() as number | null)}</div>,
    },
    {
        id: 'cost',
        header: 'Cost',
        accessorFn: (row) => row.totalCostUsd,
        cell: ({ getValue }) => <div className="text-right whitespace-nowrap">{formatCost(getValue() as string | number | null)}</div>,
    },
];

export const AgentRunsTab: React.FC<AgentRunsTabProps> = ({ agentId }) => {
    const [page, setPage] = useState(0);

    const { data, isLoading, isFetching, error, refetch } = useQuery({
        queryKey: ['agents', 'runs', agentId, page, PAGE_SIZE],
        queryFn: () => runsApi.list({ agentId, page, size: PAGE_SIZE }),
        staleTime: 30_000,
    });

    if (error) {
        return (
            <Alert severity="error" title="Failed to load runs">
                {(error as Error).message}
            </Alert>
        );
    }

    return (
        <div className="space-y-3">
            <div className="flex items-center justify-between text-xs">
                <span className="text-(--theme-muted)">
                    {data ? `${data.page.totalElements.toLocaleString()} runs` : 'Loading…'}
                </span>
                <Button
                    variant="outline"
                    size="sm"
                    onClick={() => refetch()}
                    disabled={isFetching}
                    className="gap-2"
                >
                    {isFetching ? <span className="loading loading-spinner loading-sm" /> : <LuRefreshCw className="w-4 h-4" />}
                    Refresh
                </Button>
            </div>

            {isLoading && !data ? (
                <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl overflow-hidden p-4 space-y-2">
                    {[1, 2, 3].map(i => (
                        <div key={i} className="h-10 bg-obsidian-elevated/50 rounded animate-pulse" />
                    ))}
                </div>
            ) : data ? (
                <DataTable
                    columns={RUN_COLUMNS}
                    data={data.content}
                    manualPagination
                    pageIndex={page}
                    pageSize={PAGE_SIZE}
                    totalElements={data.page.totalElements}
                    onPageChange={setPage}
                    emptyMessage="No runs for this agent yet."
                />
            ) : null}
        </div>
    );
};
