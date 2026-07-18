import React, { useState, useMemo } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { ColumnDef } from '@tanstack/react-table';
import { LuCheck, LuX, LuRefreshCw, LuUserCheck } from 'react-icons/lu';
import { orchestrationApi } from '../../../shared/api/orchestrationApi';
import type { HumanReviewPending } from '../../../shared/types/orchestration';
import { Alert } from '../../../shared/components/ui/Alert';
import { Badge } from '../../../shared/components/ui/Badge';
import { Button } from '../../../shared/components/ui/Button';
import { DataTable } from '../../../shared/components/ui/DataTable';

const QUERY_KEY = ['approvals', 'human-review'];

const formatAge = (iso: string): string => {
    const mins = (Date.now() - new Date(iso).getTime()) / 60_000;
    if (Number.isNaN(mins)) return '—';
    if (mins < 60) return `${Math.floor(mins)}m`;
    const hours = mins / 60;
    return hours < 24 ? `${Math.floor(hours)}h` : `${Math.floor(hours / 24)}d`;
};

const subjectVariant = (s: string): 'info' | 'warning' | 'ghost' => {
    if (s === 'WORKFLOW_STEP') return 'info';
    if (s === 'TEAM_MEMBER_DISPATCH') return 'warning';
    return 'ghost';
};

export const HumanReviewQueue: React.FC = () => {
    const queryClient = useQueryClient();
    const [actionError, setActionError] = useState<string | null>(null);

    const { data, isLoading, isFetching, error, refetch } = useQuery({
        queryKey: QUERY_KEY,
        queryFn: () => orchestrationApi.listHumanReviewPending(),
        staleTime: 15_000,
        refetchInterval: 30_000,
    });

    const decideMutation = useMutation({
        mutationFn: ({ id, decision }: { id: string; decision: 'approve' | 'reject' }) =>
            orchestrationApi.decideHumanReview(id, decision),
        onSuccess: () => {
            setActionError(null);
            queryClient.invalidateQueries({ queryKey: QUERY_KEY });
        },
        onError: (err: unknown) => {
            const status = (err as { status?: number })?.status;
            if (status === 403) {
                setActionError('Only an admin may decide a HumanReview pause.');
            } else if (status === 404) {
                setActionError('That pending item no longer exists — it may have been decided or expired.');
                queryClient.invalidateQueries({ queryKey: QUERY_KEY });
            } else {
                setActionError(err instanceof Error ? err.message : 'Failed to record the decision.');
            }
        },
    });

    const pendings = data ?? [];
    const decidingId = decideMutation.isPending ? decideMutation.variables?.id : undefined;

    const decideMutate = decideMutation.mutate;
    const columns = useMemo<ColumnDef<HumanReviewPending, unknown>[]>(() => [
        {
            accessorKey: 'subjectType',
            header: 'Subject',
            cell: ({ getValue }) => (
                <Badge variant={subjectVariant(getValue() as string)} className="text-xs">{getValue() as string}</Badge>
            ),
        },
        {
            accessorKey: 'reason',
            header: 'Reason',
            cell: ({ getValue }) => {
                const reason = getValue() as string;
                return (
                    <div className="max-w-[360px] truncate" title={reason}>
                        {reason || <span className="text-(--theme-muted)">—</span>}
                    </div>
                );
            },
        },
        {
            accessorKey: 'runId',
            header: 'Run',
            cell: ({ getValue }) => {
                const runId = getValue() as string;
                return <span className="font-mono text-xs" title={runId}>{runId.slice(0, 12)}…</span>;
            },
        },
        {
            accessorKey: 'createdAt',
            header: 'Age',
            cell: ({ getValue }) => (
                <div className="text-xs text-(--theme-muted) text-right whitespace-nowrap">{formatAge(getValue() as string)}</div>
            ),
        },
        {
            id: 'decision',
            header: 'Decision',
            enableSorting: false,
            cell: ({ row }) => {
                const busy = decidingId === row.original.id;
                return (
                    <div className="flex items-center justify-end gap-1">
                        <Button
                            size="sm"
                            variant="ghost"
                            className="gap-1 text-success hover:bg-success/10"
                            disabled={busy}
                            onClick={() => decideMutate({ id: row.original.id, decision: 'approve' })}
                            title="Approve"
                        >
                            <LuCheck className="w-3.5 h-3.5" /> Approve
                        </Button>
                        <Button
                            size="sm"
                            variant="ghost"
                            className="gap-1 text-error hover:bg-error/10"
                            disabled={busy}
                            onClick={() => decideMutate({ id: row.original.id, decision: 'reject' })}
                            title="Reject"
                        >
                            <LuX className="w-3.5 h-3.5" /> Reject
                        </Button>
                    </div>
                );
            },
        },
    ], [decidingId, decideMutate]);

    if (error) {
        return (
            <Alert severity="error" title="Failed to load HumanReview queue">
                {(error as Error).message}
            </Alert>
        );
    }

    return (
        <div className="space-y-3">
            <div className="flex items-center justify-between">
                <span className="text-xs text-(--theme-muted)">
                    {pendings.length} awaiting decision · unified HITL pauses (workflow / team / tool gates)
                </span>
                <Button
                    variant="outline"
                    size="sm"
                    onClick={() => { void refetch(); }}
                    disabled={isFetching}
                    className="gap-2"
                >
                    {isFetching
                        ? <span className="loading loading-spinner loading-sm" />
                        : <LuRefreshCw className="w-4 h-4" />}
                    Refresh
                </Button>
            </div>

            {actionError && (
                <Alert severity="error" description={actionError} dismissible onClose={() => setActionError(null)} />
            )}

            {isLoading && pendings.length === 0 ? (
                <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl overflow-hidden p-4 space-y-2">
                    {[1, 2, 3].map(i => <div key={i} className="h-12 bg-obsidian-elevated/50 rounded animate-pulse" />)}
                </div>
            ) : pendings.length === 0 ? (
                <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl overflow-hidden p-8 text-center text-(--theme-muted) flex flex-col items-center gap-2">
                    <LuUserCheck className="w-6 h-6 opacity-50" />
                    No HumanReview pauses awaiting a decision.
                </div>
            ) : (
                <DataTable columns={columns} data={pendings} enablePagination defaultPageSize={10} />
            )}
        </div>
    );
};
