import React, { useMemo } from 'react';
import type { ColumnDef } from '@tanstack/react-table';
import { Badge } from '../../../shared/components/ui/Badge';
import { Button } from '../../../shared/components/ui/Button';
import { DataTable } from '../../../shared/components/ui/DataTable';
import { LuActivity, LuX, LuCircleAlert, LuCircleCheck, LuPause, LuPlay, LuClock } from 'react-icons/lu';
import type { A2aTaskStatus } from '../api/a2aApi';

/** A live, in-page view of a submitted A2A task. */
export interface ActiveA2aTask {
    /** Client-pre-generated UUID — also passed as A2aTaskRequest.taskId so the
     *  cancel endpoint works from the moment we hit Submit. */
    taskId: string;
    alias: string;
    targetAgentId: string;
    input: string;
    /** CONNECTING is the local-only state between Submit and the first SSE
     *  event landing. After that, this is always one of A2aTaskStatus. */
    status: A2aTaskStatus | 'CONNECTING';
    lastMessage: string | null;
    errorDetail: string | null;
    /** Server-issued run id once the task hits WORKING. Null before that. */
    runId: string | null;
    startedAt: number;
    updatedAt: number;
    /** Set true between the cancel click and the server's terminal-status SSE
     *  event. Keeps the cancel button locked and the row visually busy. */
    cancelling: boolean;
}

const STATUS_ICON: Record<ActiveA2aTask['status'], React.ReactElement> = {
    CONNECTING: <span className="loading loading-spinner loading-xs" />,
    SUBMITTED: <LuClock className="w-3 h-3" />,
    WORKING: <LuPlay className="w-3 h-3" />,
    PAUSED: <LuPause className="w-3 h-3" />,
    COMPLETED: <LuCircleCheck className="w-3 h-3" />,
    FAILED: <LuCircleAlert className="w-3 h-3" />,
    CANCELLED: <LuX className="w-3 h-3" />,
    BUDGET_HALT: <LuCircleAlert className="w-3 h-3" />,
};

const STATUS_VARIANT: Record<ActiveA2aTask['status'], 'neutral' | 'info' | 'warning' | 'success' | 'error'> = {
    CONNECTING: 'neutral',
    SUBMITTED: 'neutral',
    WORKING: 'info',
    PAUSED: 'warning',
    COMPLETED: 'success',
    FAILED: 'error',
    CANCELLED: 'error',
    BUDGET_HALT: 'error',
};

interface Props {
    tasks: ActiveA2aTask[];
    onCancel: (taskId: string) => void;
    onClearTerminal: () => void;
}

const formatRelative = (ts: number): string => {
    const seconds = Math.max(0, Math.floor((Date.now() - ts) / 1000));
    if (seconds < 60) return `${seconds}s ago`;
    const minutes = Math.floor(seconds / 60);
    if (minutes < 60) return `${minutes}m ago`;
    const hours = Math.floor(minutes / 60);
    return `${hours}h ago`;
};

export const A2aActiveTasksPanel: React.FC<Props> = ({ tasks, onCancel, onClearTerminal }) => {
    const hasTerminal = tasks.some(t =>
        t.status === 'COMPLETED' || t.status === 'FAILED' || t.status === 'CANCELLED',
    );

    const columns = useMemo<ColumnDef<ActiveA2aTask, unknown>[]>(() => [
        {
            accessorKey: 'status',
            header: 'Status',
            cell: ({ row }) => {
                const t = row.original;
                return (
                    <div className="flex items-center gap-1.5">
                        <Badge variant={STATUS_VARIANT[t.status]} outline className="text-xs gap-1 font-mono">
                            {STATUS_ICON[t.status]}
                            {t.status}
                        </Badge>
                        {t.cancelling && (
                            <span className="text-[10px] text-warn-amber font-mono">cancelling…</span>
                        )}
                    </div>
                );
            },
        },
        {
            accessorKey: 'alias',
            header: 'Target',
            cell: ({ row }) => (
                <div className="min-w-0">
                    <div className="font-medium text-sm truncate max-w-40" title={row.original.alias}>
                        {row.original.alias}
                    </div>
                    <div className="font-mono text-[10px] text-(--theme-muted) truncate max-w-50" title={row.original.targetAgentId}>
                        {row.original.targetAgentId}
                    </div>
                </div>
            ),
        },
        {
            accessorKey: 'input',
            header: 'Input',
            cell: ({ getValue }) => (
                <span className="text-xs text-(--theme-muted) line-clamp-2 max-w-60 break-words" title={getValue() as string}>
                    {getValue() as string}
                </span>
            ),
        },
        {
            id: 'message',
            header: 'Last update',
            cell: ({ row }) => {
                const t = row.original;
                if (t.errorDetail) {
                    return (
                        <span className="text-xs text-error font-mono line-clamp-2 max-w-60 break-words" title={t.errorDetail}>
                            {t.errorDetail}
                        </span>
                    );
                }
                if (t.lastMessage) {
                    return (
                        <span className="text-xs text-(--theme-muted) line-clamp-2 max-w-60 break-words" title={t.lastMessage}>
                            {t.lastMessage}
                        </span>
                    );
                }
                return <span className="text-xs text-(--theme-muted) italic opacity-60">—</span>;
            },
        },
        {
            id: 'runId',
            header: 'Run',
            cell: ({ row }) => row.original.runId
                ? <code className="text-[10px] font-mono text-(--theme-muted)" title={row.original.runId}>{row.original.runId.slice(0, 8)}…</code>
                : <span className="text-[10px] text-(--theme-muted) opacity-50">—</span>,
        },
        {
            id: 'updated',
            header: 'Updated',
            cell: ({ row }) => (
                <span className="text-[10px] text-(--theme-muted) whitespace-nowrap">
                    {formatRelative(row.original.updatedAt)}
                </span>
            ),
        },
        {
            id: 'actions',
            header: '',
            enableSorting: false,
            cell: ({ row }) => {
                const t = row.original;
                const isTerminal = t.status === 'COMPLETED' || t.status === 'FAILED' || t.status === 'CANCELLED';
                if (isTerminal) return <span className="text-[10px] text-(--theme-muted) opacity-50">—</span>;
                return (
                    <div className="flex justify-end">
                        <Button
                            variant="ghost"
                            size="sm"
                            className="text-error hover:bg-error/10 gap-1"
                            disabled={t.cancelling}
                            onClick={() => onCancel(t.taskId)}
                            title="Cancel this task"
                        >
                            <LuX className="w-3 h-3" /> Cancel
                        </Button>
                    </div>
                );
            },
        },
    ], [onCancel]);

    return (
        <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl p-5 space-y-3 shadow-sm">
            <div className="flex items-center justify-between gap-3">
                <div>
                    <h3 className="text-sm font-bold uppercase tracking-wider text-(--theme-muted) flex items-center gap-2">
                        <LuActivity className="w-3.5 h-3.5" /> Active Tasks
                        <Badge variant="ghost" className="text-[10px] font-mono">{tasks.length}</Badge>
                    </h3>
                    <p className="text-xs text-(--theme-muted) mt-1">
                        In-page only — refreshing the page drops the list. Status updates stream over SSE; terminal rows stay visible until cleared.
                    </p>
                </div>
                {hasTerminal && (
                    <Button variant="ghost" size="sm" onClick={onClearTerminal} className="gap-1 text-(--theme-muted)">
                        Clear completed
                    </Button>
                )}
            </div>

            <DataTable
                columns={columns}
                data={tasks}
                enablePagination
                defaultPageSize={25}
                compact
                emptyMessage="No active tasks yet. Submit one above to see lifecycle events stream in."
            />
        </div>
    );
};
