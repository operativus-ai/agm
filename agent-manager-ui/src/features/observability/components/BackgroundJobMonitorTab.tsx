import React, { useState, useMemo } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { ColumnDef } from '@tanstack/react-table';
import { LuRefreshCw, LuRotateCcw, LuPause, LuPlay, LuTriangleAlert } from 'react-icons/lu';
import { Alert } from '../../../shared/components/ui/Alert';
import { Badge } from '../../../shared/components/ui/Badge';
import { Button } from '../../../shared/components/ui/Button';
import { DataTable } from '../../../shared/components/ui/DataTable';
import {
    backgroundJobApi,
    classifyRetryError,
    type BackgroundJob,
    type JobStatus,
} from '../api/backgroundJobApi';

const PAGE_SIZE = 25;

const statusVariant = (s: JobStatus): 'success' | 'error' | 'warning' | 'info' | 'ghost' => {
    if (s === 'COMPLETED') return 'success';
    if (s === 'FAILED' || s === 'DLQ') return 'error';
    if (s === 'PROCESSING') return 'info';
    if (s === 'QUEUED' || s === 'PAUSED') return 'warning';
    return 'ghost';
};

const formatTimestamp = (s: string | null): string => {
    if (!s) return '—';
    const d = new Date(s);
    return Number.isNaN(d.getTime()) ? '—' : d.toLocaleString();
};

interface ToastMessage {
    id: number;
    text: string;
    tone: 'success' | 'warning' | 'error';
}

let toastSeq = 0;

export const BackgroundJobMonitorTab: React.FC = () => {
    const [pageIndex, setPageIndex] = useState(0);
    const [statusFilter, setStatusFilter] = useState<JobStatus | ''>('');
    const [hiddenIds, setHiddenIds] = useState<Set<string>>(new Set());
    const [maxRetriesIds, setMaxRetriesIds] = useState<Set<string>>(new Set());
    const [toasts, setToasts] = useState<ToastMessage[]>([]);
    const queryClient = useQueryClient();

    const queryKey = ['observability', 'background-jobs', { status: statusFilter || null, pageIndex, PAGE_SIZE }];

    const { data, isLoading, isFetching, error, refetch } = useQuery({
        queryKey,
        queryFn: () => backgroundJobApi.list({
            status: statusFilter || undefined,
            page: pageIndex,
            size: PAGE_SIZE,
        }),
        staleTime: 15_000,
        refetchInterval: 30_000,
    });

    const summaryQuery = useQuery({
        queryKey: ['observability', 'background-jobs', 'status-summary'],
        queryFn: () => backgroundJobApi.statusSummary(),
        staleTime: 15_000,
        refetchInterval: 30_000,
    });

    const pauseStateQuery = useQuery({
        queryKey: ['observability', 'background-jobs', 'pause-state'],
        queryFn: () => backgroundJobApi.getPauseState(),
        staleTime: 15_000,
        refetchInterval: 30_000,
    });
    const isPaused = pauseStateQuery.data?.paused ?? false;

    const showToast = (text: string, tone: ToastMessage['tone']) => {
        const id = ++toastSeq;
        setToasts(prev => [...prev, { id, text, tone }]);
        setTimeout(() => setToasts(prev => prev.filter(t => t.id !== id)), 4000);
    };

    const retryMutation = useMutation({
        mutationFn: (id: string) => backgroundJobApi.retry(id),
        onSuccess: (_data, id) => {
            showToast(`Retry queued for job ${id}.`, 'success');
            queryClient.invalidateQueries({ queryKey: ['observability', 'background-jobs'] });
        },
        onError: (err, id) => {
            const reason = classifyRetryError(err);
            if (reason === 'not_found') {
                showToast(`Job ${id} no longer exists.`, 'warning');
                setHiddenIds(prev => new Set(prev).add(id));
            } else if (reason === 'not_failed') {
                showToast(`Job ${id} is not in a failed state.`, 'warning');
                queryClient.invalidateQueries({ queryKey: ['observability', 'background-jobs'] });
            } else if (reason === 'max_retries') {
                showToast(`Job ${id} has reached its retry cap.`, 'error');
                setMaxRetriesIds(prev => new Set(prev).add(id));
            } else {
                showToast((err as Error).message || `Retry failed for job ${id}.`, 'error');
            }
        },
    });

    const togglePauseMutation = useMutation({
        mutationFn: (nextPaused: boolean) =>
            nextPaused ? backgroundJobApi.pause() : backgroundJobApi.resume(),
        onSuccess: (_data, nextPaused) => {
            showToast(nextPaused ? 'Background job queue paused.' : 'Background job queue resumed.', nextPaused ? 'warning' : 'success');
            queryClient.invalidateQueries({ queryKey: ['observability', 'background-jobs'] });
        },
        onError: (err) => {
            showToast((err as Error).message || 'Failed to change queue pause state.', 'error');
        },
    });

    const retryMutate = retryMutation.mutate;
    const retryPending = retryMutation.isPending;
    const retryVariable = retryMutation.variables;
    const columns = useMemo<ColumnDef<BackgroundJob, unknown>[]>(() => [
        {
            accessorKey: 'status',
            header: 'Status',
            cell: ({ getValue }) => <Badge variant={statusVariant(getValue() as JobStatus)} className="text-xs">{getValue() as string}</Badge>,
        },
        {
            accessorKey: 'id',
            header: 'Job',
            cell: ({ getValue }) => (
                <span className="font-mono text-xs truncate max-w-[200px] inline-block align-bottom" title={getValue() as string}>{getValue() as string}</span>
            ),
        },
        { accessorKey: 'jobType', header: 'Type', cell: ({ getValue }) => <span className="text-xs">{(getValue() as string) ?? '—'}</span> },
        { accessorKey: 'agentId', header: 'Agent', cell: ({ getValue }) => <span className="font-mono text-xs">{(getValue() as string) ?? '—'}</span> },
        {
            id: 'retries',
            header: 'Retries',
            accessorFn: (row) => row.retryCount,
            cell: ({ row }) => <span className="text-xs whitespace-nowrap">{row.original.retryCount} / {row.original.maxRetries}</span>,
        },
        {
            accessorKey: 'createdAt',
            header: 'Created',
            cell: ({ getValue }) => <span className="text-xs text-(--theme-muted) whitespace-nowrap">{formatTimestamp(getValue() as string | null)}</span>,
        },
        {
            accessorKey: 'errorMessage',
            header: 'Last error',
            cell: ({ getValue }) => {
                const msg = getValue() as string | null;
                return (
                    <span className="text-xs truncate max-w-[280px] inline-block align-bottom" title={msg ?? ''}>
                        {msg ?? <span className="text-(--theme-muted)">—</span>}
                    </span>
                );
            },
        },
        {
            id: 'retry',
            header: '',
            enableSorting: false,
            cell: ({ row }) => {
                const job = row.original;
                const canRetry = !maxRetriesIds.has(job.id) && (job.status === 'FAILED' || job.status === 'DLQ');
                if (!canRetry) return null;
                const retryDisabled = retryPending && retryVariable === job.id;
                return (
                    <div className="text-right">
                        <Button
                            size="sm"
                            variant="ghost"
                            className="px-2 text-(--theme-muted) hover:text-primary"
                            onClick={() => retryMutate(job.id)}
                            disabled={retryDisabled}
                            title="Retry job"
                        >
                            <LuRotateCcw className="w-3.5 h-3.5" />
                        </Button>
                    </div>
                );
            },
        },
    ], [maxRetriesIds, retryPending, retryVariable, retryMutate]);

    if (error) {
        return (
            <Alert severity="error" title="Failed to load background jobs">
                {(error as Error).message}
            </Alert>
        );
    }

    const allJobs = data?.content ?? [];
    const jobs = allJobs.filter(j => !hiddenIds.has(j.id));
    const totalElements = data?.page.totalElements ?? 0;

    const setFilter = (next: JobStatus | '') => {
        setStatusFilter(next);
        setPageIndex(0);
    };

    return (
        <div className="space-y-4">
            {isPaused && (
                <div className="flex items-center gap-2 px-4 py-2 rounded-lg border border-warning/30 bg-warning/10 text-sm text-(--theme-foreground)">
                    <LuTriangleAlert className="w-4 h-4 text-warning shrink-0" />
                    <span>
                        The background job queue is <span className="font-semibold">paused</span> — QUEUED jobs are not being
                        picked up. Use <span className="font-semibold">Resume queue</span> to restart processing.
                    </span>
                </div>
            )}

            <StatusSummary
                summary={summaryQuery.data}
                activeFilter={statusFilter}
                onSelect={setFilter}
                isLoading={summaryQuery.isLoading}
            />

            <div className="flex items-center justify-between">
                <div className="flex items-center gap-2 text-xs">
                    <span className="text-(--theme-muted)">{totalElements.toLocaleString()} total</span>
                    <select
                        className="select select-xs bg-obsidian-base border-obsidian-stroke"
                        value={statusFilter}
                        onChange={(e) => setFilter(e.target.value as JobStatus | '')}
                    >
                        <option value="">All statuses</option>
                        <option value="QUEUED">Queued</option>
                        <option value="PROCESSING">Processing</option>
                        <option value="PAUSED">Paused</option>
                        <option value="COMPLETED">Completed</option>
                        <option value="FAILED">Failed</option>
                        <option value="DLQ">DLQ</option>
                    </select>
                </div>
                <div className="flex items-center gap-2">
                    <Button
                        variant={isPaused ? 'primary' : 'outline'}
                        size="sm"
                        onClick={() => togglePauseMutation.mutate(!isPaused)}
                        disabled={togglePauseMutation.isPending || pauseStateQuery.isLoading}
                        className="gap-2"
                        title={isPaused ? 'Resume the background job queue' : 'Pause the background job queue (QUEUED jobs stop being picked up)'}
                    >
                        {togglePauseMutation.isPending
                            ? <span className="loading loading-spinner loading-sm" />
                            : isPaused ? <LuPlay className="w-4 h-4" /> : <LuPause className="w-4 h-4" />}
                        {isPaused ? 'Resume queue' : 'Pause queue'}
                    </Button>
                    <Button
                        variant="outline"
                        size="sm"
                        onClick={() => { void refetch(); void summaryQuery.refetch(); void pauseStateQuery.refetch(); }}
                        disabled={isFetching || summaryQuery.isFetching}
                        className="gap-2"
                    >
                        {(isFetching || summaryQuery.isFetching)
                            ? <span className="loading loading-spinner loading-sm" />
                            : <LuRefreshCw className="w-4 h-4" />}
                        Refresh
                    </Button>
                </div>
            </div>

            {isLoading && jobs.length === 0 ? (
                <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl overflow-hidden p-4 space-y-2">
                    {[1, 2, 3, 4].map(i => (
                        <div key={i} className="h-10 bg-obsidian-elevated/50 rounded animate-pulse" />
                    ))}
                </div>
            ) : (
                <DataTable
                    columns={columns}
                    data={jobs}
                    manualPagination
                    pageIndex={pageIndex}
                    pageSize={PAGE_SIZE}
                    totalElements={totalElements}
                    onPageChange={setPageIndex}
                    emptyMessage={statusFilter ? `No ${statusFilter.toLowerCase()} jobs.` : 'No background jobs.'}
                />
            )}

            {toasts.length > 0 && (
                <div className="fixed bottom-6 right-6 flex flex-col gap-2 z-50 max-w-sm">
                    {toasts.map(t => (
                        <div
                            key={t.id}
                            className={`px-4 py-2 rounded-lg shadow-lg border text-sm ${
                                t.tone === 'success'
                                    ? 'bg-success/10 border-success/30 text-(--theme-foreground)'
                                    : t.tone === 'warning'
                                    ? 'bg-warning/10 border-warning/30 text-(--theme-foreground)'
                                    : 'bg-error/10 border-error/30 text-(--theme-foreground)'
                            }`}
                        >
                            {t.text}
                        </div>
                    ))}
                </div>
            )}
        </div>
    );
};

const STATUS_ORDER: JobStatus[] = ['QUEUED', 'PROCESSING', 'PAUSED', 'COMPLETED', 'FAILED', 'DLQ'];

const STATUS_COLOR: Record<JobStatus, string> = {
    QUEUED: '#f59e0b',
    PROCESSING: '#3b82f6',
    PAUSED: '#a3a3a3',
    COMPLETED: '#10b981',
    FAILED: '#ef4444',
    DLQ: '#7f1d1d',
};

interface StatusSummaryProps {
    summary: Record<JobStatus, number> | undefined;
    activeFilter: JobStatus | '';
    onSelect: (next: JobStatus | '') => void;
    isLoading: boolean;
}

const StatusSummary: React.FC<StatusSummaryProps> = ({ summary, activeFilter, onSelect, isLoading }) => {
    if (isLoading && !summary) {
        return <div className="h-16 bg-obsidian-elevated/50 rounded-xl animate-pulse" />;
    }
    if (!summary) return null;

    const total = STATUS_ORDER.reduce((sum, s) => sum + (summary[s] ?? 0), 0);

    return (
        <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl px-4 py-3 space-y-2">
            <div className="flex items-center justify-between text-[11px] uppercase tracking-wider text-(--theme-muted)">
                <span>Current state · {total.toLocaleString()} jobs total</span>
                {activeFilter && (
                    <button
                        type="button"
                        onClick={() => onSelect('')}
                        className="text-[11px] normal-case tracking-normal hover:underline"
                    >
                        Clear filter
                    </button>
                )}
            </div>
            {total === 0 ? (
                <div className="text-(--theme-muted) text-sm py-2">No background jobs.</div>
            ) : (
                <>
                    <div className="flex h-3 w-full rounded-full overflow-hidden bg-obsidian-elevated/40">
                        {STATUS_ORDER.map(s => {
                            const count = summary[s] ?? 0;
                            if (count === 0) return null;
                            return (
                                <button
                                    key={s}
                                    type="button"
                                    onClick={() => onSelect(activeFilter === s ? '' : s)}
                                    title={`${s}: ${count.toLocaleString()}`}
                                    className="h-full transition-opacity hover:opacity-80 focus:outline-none focus:ring-2 focus:ring-offset-1 focus:ring-(--theme-foreground)"
                                    style={{ flexGrow: count, background: STATUS_COLOR[s] }}
                                    aria-label={`Filter to ${s} (${count})`}
                                />
                            );
                        })}
                    </div>
                    <div className="flex flex-wrap items-center gap-2 text-xs">
                        {STATUS_ORDER.map(s => {
                            const count = summary[s] ?? 0;
                            const active = activeFilter === s;
                            return (
                                <button
                                    key={s}
                                    type="button"
                                    onClick={() => onSelect(active ? '' : s)}
                                    className={`flex items-center gap-1.5 px-2 py-0.5 rounded-full border transition-colors ${
                                        active
                                            ? 'border-(--theme-foreground)/40 bg-(--theme-muted)/10 text-(--theme-foreground)'
                                            : 'border-(--theme-muted)/20 text-(--theme-muted) hover:text-(--theme-foreground)'
                                    }`}
                                >
                                    <span
                                        className="inline-block w-2 h-2 rounded-full"
                                        style={{ background: STATUS_COLOR[s] }}
                                    />
                                    <span className="font-mono text-[11px]">{s}</span>
                                    <span className="font-semibold">{count.toLocaleString()}</span>
                                </button>
                            );
                        })}
                    </div>
                </>
            )}
        </div>
    );
};
