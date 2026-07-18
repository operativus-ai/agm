import React, { useState, useMemo } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import type { ColumnDef } from '@tanstack/react-table';
import { LuCheck, LuRefreshCw } from 'react-icons/lu';
import { Alert } from '../../../shared/components/ui/Alert';
import { Badge } from '../../../shared/components/ui/Badge';
import { Button } from '../../../shared/components/ui/Button';
import { DataTable } from '../../../shared/components/ui/DataTable';
import { AlertsApi, type AlertEvent } from '../../alerts/api/alertsApi';

const PAGE_SIZE = 25;

const severityVariant = (s: string): 'error' | 'warning' | 'info' | 'ghost' => {
    const norm = (s ?? '').toUpperCase();
    if (norm === 'CRITICAL' || norm === 'ERROR' || norm === 'HIGH') return 'error';
    if (norm === 'WARNING' || norm === 'WARN' || norm === 'MEDIUM') return 'warning';
    if (norm === 'INFO' || norm === 'LOW') return 'info';
    return 'ghost';
};

const formatTimestamp = (s: string): string => {
    const d = new Date(s);
    return Number.isNaN(d.getTime()) ? '—' : d.toLocaleString();
};

export const AlertEventHistoryPanel: React.FC = () => {
    const [pageIndex, setPageIndex] = useState(0);
    const queryClient = useQueryClient();

    const { data, isLoading, isFetching, error, refetch } = useQuery({
        queryKey: ['alerts', 'history', pageIndex, PAGE_SIZE],
        queryFn: () => AlertsApi.listEvents({ page: pageIndex, size: PAGE_SIZE }),
        staleTime: 15_000,
        refetchInterval: 60_000,
    });

    const ack = useMutation({
        mutationFn: (id: string) => AlertsApi.acknowledge(id),
        onSuccess: () => {
            queryClient.invalidateQueries({ queryKey: ['alerts'] });
        },
    });

    const events = data?.content ?? [];
    const totalElements = data?.totalElements ?? 0;

    const ackMutate = ack.mutate;
    const ackPending = ack.isPending;
    const columns = useMemo<ColumnDef<AlertEvent, unknown>[]>(() => [
        {
            accessorKey: 'firedAt',
            header: 'Fired',
            cell: ({ getValue }) => (
                <span className="text-xs text-(--theme-muted) whitespace-nowrap">{formatTimestamp(getValue() as string)}</span>
            ),
        },
        {
            accessorKey: 'severity',
            header: 'Severity',
            cell: ({ getValue }) => (
                <Badge variant={severityVariant(getValue() as string)} className="text-xs">
                    {((getValue() as string) ?? '').toUpperCase() || 'UNKNOWN'}
                </Badge>
            ),
        },
        {
            accessorKey: 'ruleId',
            header: 'Rule',
            cell: ({ getValue }) => (
                <span className="font-mono text-xs truncate max-w-[200px] inline-block align-bottom" title={getValue() as string}>
                    {getValue() as string}
                </span>
            ),
        },
        { accessorKey: 'message', header: 'Message', cell: ({ getValue }) => <span className="text-xs">{getValue() as string}</span> },
        {
            accessorKey: 'metricValue',
            header: 'Value',
            cell: ({ getValue }) => {
                const v = getValue() as number;
                return <div className="text-right font-mono whitespace-nowrap">{Number.isFinite(v) ? v.toLocaleString() : '—'}</div>;
            },
        },
        {
            id: 'status',
            header: 'Status',
            accessorFn: (row) => row.acknowledged,
            cell: ({ row }) =>
                row.original.acknowledged
                    ? <span className="text-xs text-(--theme-muted)">Acknowledged</span>
                    : <span className="text-xs text-error">Active</span>,
        },
        {
            id: 'ack',
            header: '',
            enableSorting: false,
            cell: ({ row }) => (
                !row.original.acknowledged
                    ? (
                        <div className="text-right">
                            <Button
                                size="sm"
                                variant="ghost"
                                className="px-2 text-(--theme-muted) hover:text-success"
                                onClick={() => ackMutate(row.original.id)}
                                disabled={ackPending}
                                title="Acknowledge"
                            >
                                <LuCheck className="w-3.5 h-3.5" />
                            </Button>
                        </div>
                    )
                    : null
            ),
        },
    ], [ackMutate, ackPending]);

    if (error) {
        return (
            <Alert severity="error" title="Failed to load alert history">
                {(error as Error).message}
            </Alert>
        );
    }

    return (
        <div className="space-y-4">
            <div className="flex items-center justify-between">
                <div className="text-xs text-(--theme-muted)">
                    {isLoading
                        ? 'Loading…'
                        : `${totalElements.toLocaleString()} alert event${totalElements === 1 ? '' : 's'}`}
                </div>
                <Button variant="outline" size="sm" onClick={() => refetch()} disabled={isFetching} className="gap-2">
                    {isFetching ? <span className="loading loading-spinner loading-sm" /> : <LuRefreshCw className="w-4 h-4" />}
                    Refresh
                </Button>
            </div>

            {isLoading && events.length === 0 ? (
                <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl overflow-hidden p-4 space-y-2">
                    {[1, 2, 3, 4].map(i => (
                        <div key={i} className="h-10 bg-obsidian-elevated/50 rounded animate-pulse" />
                    ))}
                </div>
            ) : (
                <DataTable
                    columns={columns}
                    data={events}
                    manualPagination
                    pageIndex={pageIndex}
                    pageSize={PAGE_SIZE}
                    totalElements={totalElements}
                    onPageChange={setPageIndex}
                    emptyMessage="No alert events recorded."
                />
            )}
        </div>
    );
};
