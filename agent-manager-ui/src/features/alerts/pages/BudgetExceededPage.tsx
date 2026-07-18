import React, { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import type { ColumnDef } from '@tanstack/react-table';
import { LuCheck, LuDollarSign, LuRefreshCw } from 'react-icons/lu';
import { PageContainer } from '../../../shared/components/ui/PageContainer';
import { PageHeader } from '../../../shared/components/ui/PageHeader';
import { Alert } from '../../../shared/components/ui/Alert';
import { Button } from '../../../shared/components/ui/Button';
import { DataTable } from '../../../shared/components/ui/DataTable';
import { budgetExceededApi, type BudgetExceededEvent } from '../api/budgetExceededApi';

const ACK_STORAGE_KEY = 'agm.budget-exceeded.ackUpToEventId';
const PAGE_LIMIT = 200;

const readAck = (): number => {
    try {
        const raw = localStorage.getItem(ACK_STORAGE_KEY);
        const n = raw ? Number(raw) : 0;
        return Number.isFinite(n) ? n : 0;
    } catch {
        return 0;
    }
};

const writeAck = (id: number): void => {
    try {
        localStorage.setItem(ACK_STORAGE_KEY, String(id));
    } catch {
        /* ignore */
    }
};

const formatTimestamp = (s: string): string => {
    const d = new Date(s);
    return Number.isNaN(d.getTime()) ? '—' : d.toLocaleString();
};

const formatPayload = (e: BudgetExceededEvent): React.ReactNode => {
    const p = (e.payload ?? {}) as Record<string, unknown>;
    const limit = p.limit ?? p.budgetUsd;
    const actual = p.actual ?? p.actualUsd ?? p.totalCostUsd;
    if (limit || actual) {
        return (
            <span>
                {actual != null && <span className="font-mono">${String(actual)}</span>}
                {limit != null && (
                    <span className="text-(--theme-muted)"> {actual != null ? '/' : ''} limit ${String(limit)}</span>
                )}
            </span>
        );
    }
    return <span className="text-(--theme-muted)">—</span>;
};

const staticColumns: ColumnDef<BudgetExceededEvent>[] = [
    {
        id: 'time',
        accessorFn: row => row.eventTs,
        header: 'Time',
        cell: ctx => (
            <span className="text-xs text-(--theme-muted) whitespace-nowrap">
                {formatTimestamp(ctx.getValue() as string)}
            </span>
        ),
    },
    {
        accessorKey: 'runId',
        header: 'Run',
        cell: ctx => (
            <Link to={`/runs/${ctx.getValue()}`} className="font-mono text-xs hover:underline">
                {ctx.getValue() as string}
            </Link>
        ),
    },
    {
        accessorKey: 'agentId',
        header: 'Agent',
        cell: ctx => {
            const id = ctx.getValue() as string | null | undefined;
            return id
                ? <Link to={`/agents/${id}`} className="font-mono text-xs hover:underline">{id}</Link>
                : <span className="text-xs text-(--theme-muted)">—</span>;
        },
    },
    {
        id: 'cost',
        header: 'Cost / limit',
        cell: ctx => <span className="text-xs">{formatPayload(ctx.row.original)}</span>,
    },
];

export const BudgetExceededPage: React.FC = () => {
    const [ackUpTo, setAckUpTo] = useState<number>(() => readAck());

    const { data, isLoading, isFetching, error, refetch } = useQuery({
        queryKey: ['budget-exceeded', 'feed', { limit: PAGE_LIMIT }],
        queryFn: () => budgetExceededApi.getFeed(undefined, PAGE_LIMIT),
        staleTime: 30_000,
    });

    const events = data?.events ?? [];
    const sorted = useMemo(() => [...events].sort((a, b) => b.id - a.id), [events]);

    const ackOne = (id: number) => {
        if (id > ackUpTo) {
            writeAck(id);
            setAckUpTo(id);
        }
    };

    const ackAll = () => {
        const newest = events.reduce((max, e) => (e.id > max ? e.id : max), 0);
        if (newest > ackUpTo) {
            writeAck(newest);
            setAckUpTo(newest);
        }
    };

    const unackCount = events.filter(e => e.id > ackUpTo).length;

    const columns = useMemo<ColumnDef<BudgetExceededEvent>[]>(() => [
        ...staticColumns,
        {
            id: 'status',
            header: 'Status',
            cell: ctx => {
                const isAcknowledged = ctx.row.original.id <= ackUpTo;
                return isAcknowledged
                    ? <span className="text-xs text-(--theme-muted)">Acknowledged</span>
                    : <span className="text-xs text-error">Unacknowledged</span>;
            },
        },
        {
            id: 'actions',
            header: '',
            cell: ctx => {
                const isAcknowledged = ctx.row.original.id <= ackUpTo;
                return !isAcknowledged ? (
                    <div className="flex justify-end">
                        <Button
                            size="sm"
                            variant="ghost"
                            className="px-2 text-(--theme-muted) hover:text-success"
                            onClick={() => ackOne(ctx.row.original.id)}
                            title="Acknowledge"
                        >
                            <LuCheck className="w-3.5 h-3.5" />
                        </Button>
                    </div>
                ) : null;
            },
        },
    ], [ackUpTo]); // eslint-disable-line react-hooks/exhaustive-deps

    return (
        <PageContainer variant="dashboard">
            <PageHeader
                icon={LuDollarSign}
                title="Budget-exceeded events"
                subtitle="Runs that exceeded their configured budget cap, last 30 days."
                actions={
                    <div className="flex items-center gap-2">
                        {unackCount > 0 && (
                            <Button variant="outline" size="sm" onClick={ackAll} className="gap-2">
                                <LuCheck className="w-4 h-4" />
                                Acknowledge all
                            </Button>
                        )}
                        <Button variant="outline" size="sm" onClick={() => refetch()} disabled={isFetching} className="gap-2">
                            {isFetching ? <span className="loading loading-spinner loading-sm" /> : <LuRefreshCw className="w-4 h-4" />}
                            Refresh
                        </Button>
                    </div>
                }
            />

            {error && (
                <Alert severity="error" title="Failed to load budget-exceeded events">
                    {(error as Error).message}
                </Alert>
            )}

            {isLoading ? (
                <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl p-4 space-y-2">
                    {[1, 2, 3].map(i => (
                        <div key={i} className="h-12 bg-obsidian-elevated/50 rounded animate-pulse" />
                    ))}
                </div>
            ) : (
                <DataTable
                    columns={columns}
                    data={sorted}
                    enablePagination
                    emptyMessage="No budget-exceeded events in the last 30 days."
                />
            )}
        </PageContainer>
    );
};
