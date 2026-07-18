import React, { useState, useMemo } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import type { ColumnDef } from '@tanstack/react-table';
import {
    LuArrowLeft,
    LuRefreshCw,
    LuWaypoints,
    LuPlay,
    LuBan,
    LuGitBranch,
    LuListTree,
    LuWorkflow,
} from 'react-icons/lu';
import { PageContainer } from '../../../shared/components/ui/PageContainer';
import { PageHeader } from '../../../shared/components/ui/PageHeader';
import { Alert } from '../../../shared/components/ui/Alert';
import { Badge } from '../../../shared/components/ui/Badge';
import { Button } from '../../../shared/components/ui/Button';
import { DataTable } from '../../../shared/components/ui/DataTable';
import { Dialog } from '../../../shared/components/ui/Dialog';
import {
    workflowRunsApi,
    type WorkflowRun,
    type WorkflowRunStatus,
    type WorkflowNodeRun,
} from '../api/workflowRunsApi';
import { orchestrationApi } from '../../../shared/api/orchestrationApi';

const PAGE_SIZE = 20;

const statusVariant = (s: WorkflowRunStatus): 'success' | 'error' | 'warning' | 'info' | 'ghost' => {
    if (s === 'COMPLETED' || s === 'APPROVED') return 'success';
    if (s === 'FAILED' || s === 'REJECTED' || s === 'EXPIRED' || s === 'CANCELLED') return 'error';
    if (s === 'RUNNING' || s === 'PROCESSING') return 'info';
    if (s === 'PAUSED' || s === 'QUEUED' || s === 'PENDING'
        || s === 'AWAITING_ROUTE_SELECTION' || s === 'AWAITING_HUMAN_REVIEW') return 'warning';
    return 'ghost';
};

// A run can be cancelled while it is non-terminal. Mirrors the backend's
// WorkflowService.cancelWorkflowRun contract (terminal rows are idempotent no-ops).
const TERMINAL_STATUSES: ReadonlySet<WorkflowRunStatus> = new Set([
    'COMPLETED', 'FAILED', 'REJECTED', 'EXPIRED', 'CANCELLED', 'APPROVED',
]);
const isCancellable = (s: WorkflowRunStatus): boolean => !TERMINAL_STATUSES.has(s);

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

export const WorkflowRunHistoryPage: React.FC = () => {
    const { id } = useParams<{ id: string }>();
    const [page, setPage] = useState(0);

    const { data, isLoading, isFetching, error, refetch } = useQuery({
        queryKey: ['workflows', 'runs', id, page, PAGE_SIZE],
        queryFn: () => workflowRunsApi.list(id!, page, PAGE_SIZE),
        enabled: Boolean(id),
        staleTime: 30_000,
    });

    // Resume modal state
    const [resumeTarget, setResumeTarget] = useState<WorkflowRun | null>(null);
    const [resumeOutput, setResumeOutput] = useState('');
    const [resuming, setResuming] = useState(false);
    const [resumeError, setResumeError] = useState<string | null>(null);
    const [resumeFeedback, setResumeFeedback] = useState<string | null>(null);

    const handleResumeOpen = (run: WorkflowRun) => {
        setResumeTarget(run);
        setResumeOutput('');
        setResumeError(null);
    };

    const handleResumeSubmit = async () => {
        if (!resumeTarget) return;
        if (!resumeOutput.trim()) {
            setResumeError('Output is required.');
            return;
        }
        setResuming(true);
        setResumeError(null);
        try {
            const res = await orchestrationApi.resumeWorkflowRun(resumeTarget.id, resumeOutput.trim());
            setResumeFeedback(
                `Resume queued for run ${res.runId.slice(0, 8)}… (job ${res.jobId.slice(0, 8)}…). Refreshing…`,
            );
            setResumeTarget(null);
            refetch();
        } catch (err) {
            setResumeError(err instanceof Error ? err.message : 'Resume submission failed.');
        } finally {
            setResuming(false);
        }
    };

    // Cancel state — confirm-only (no payload)
    const [cancelTarget, setCancelTarget] = useState<WorkflowRun | null>(null);
    const [cancelling, setCancelling] = useState(false);
    const [cancelError, setCancelError] = useState<string | null>(null);
    const [cancelFeedback, setCancelFeedback] = useState<string | null>(null);

    const handleCancelSubmit = async () => {
        if (!cancelTarget) return;
        setCancelling(true);
        setCancelError(null);
        try {
            await orchestrationApi.cancelWorkflowRun(cancelTarget.id);
            setCancelFeedback(`Cancellation requested for run ${cancelTarget.id.slice(0, 8)}…. Refreshing…`);
            setCancelTarget(null);
            refetch();
        } catch (err) {
            setCancelError(err instanceof Error ? err.message : 'Cancellation failed.');
        } finally {
            setCancelling(false);
        }
    };

    // Router-choice picker state (runs paused at a ROUTER HITL gate)
    const [routeTarget, setRouteTarget] = useState<WorkflowRun | null>(null);
    const [routeChoices, setRouteChoices] = useState<string[]>([]);
    const [routeDefault, setRouteDefault] = useState<string | null>(null);
    const [routeLoading, setRouteLoading] = useState(false);
    const [continuing, setContinuing] = useState(false);
    const [routeError, setRouteError] = useState<string | null>(null);
    const [routeFeedback, setRouteFeedback] = useState<string | null>(null);

    const handleRouteOpen = async (run: WorkflowRun) => {
        setRouteTarget(run);
        setRouteChoices([]);
        setRouteDefault(null);
        setRouteError(null);
        setRouteLoading(true);
        try {
            const opts = await orchestrationApi.getWorkflowRouteOptions(run.id);
            setRouteChoices(opts.choiceKeys);
            setRouteDefault(opts.defaultChoice);
            if (!opts.awaitingRouteSelection) {
                setRouteError('This run is no longer awaiting a route selection.');
            }
        } catch (err) {
            setRouteError(err instanceof Error ? err.message : 'Failed to load route options.');
        } finally {
            setRouteLoading(false);
        }
    };

    const handleRouteSelect = async (choiceKey: string) => {
        if (!routeTarget) return;
        setContinuing(true);
        setRouteError(null);
        try {
            const res = await orchestrationApi.continueWorkflowRun(routeTarget.id, choiceKey);
            setRouteFeedback(
                `Route '${choiceKey}' selected for run ${res.runId.slice(0, 8)}… (job ${res.jobId.slice(0, 8)}…). Refreshing…`,
            );
            setRouteTarget(null);
            refetch();
        } catch (err) {
            setRouteError(err instanceof Error ? err.message : 'Failed to continue the run.');
        } finally {
            setContinuing(false);
        }
    };

    // DAG node-run trace state (the per-node execution the frontier scheduler produced)
    const [traceTarget, setTraceTarget] = useState<WorkflowRun | null>(null);
    const [traceRows, setTraceRows] = useState<WorkflowNodeRun[]>([]);
    const [traceLoading, setTraceLoading] = useState(false);
    const [traceError, setTraceError] = useState<string | null>(null);

    const handleTraceOpen = async (run: WorkflowRun) => {
        setTraceTarget(run);
        setTraceRows([]);
        setTraceError(null);
        setTraceLoading(true);
        try {
            setTraceRows(await workflowRunsApi.nodeRuns(run.id));
        } catch (err) {
            setTraceError(err instanceof Error ? err.message : 'Failed to load the node trace.');
        } finally {
            setTraceLoading(false);
        }
    };

    const nodeKindBadge = (kind: string): 'success' | 'error' | 'warning' | 'info' | 'ghost' => {
        if (kind === 'CONDITION') return 'warning';
        if (kind === 'ROUTER') return 'info';
        return 'ghost';
    };

    const traceColumns = useMemo<ColumnDef<WorkflowNodeRun, unknown>[]>(() => [
        {
            id: 'index',
            header: '#',
            cell: ({ row }) => <span className="text-(--theme-muted)">{row.index + 1}</span>,
        },
        {
            accessorKey: 'nodeName',
            header: 'Node',
            cell: ({ row }) => {
                const n = row.original;
                return (
                    <span className="font-mono max-w-[14rem] truncate inline-block align-bottom" title={n.content ?? n.nodeName ?? n.nodeId}>
                        {n.nodeName ?? n.nodeId.slice(0, 12)}
                    </span>
                );
            },
        },
        {
            accessorKey: 'kind',
            header: 'Kind',
            cell: ({ getValue }) => {
                const kind = getValue() as string;
                return <Badge variant={nodeKindBadge(kind)} className="text-[10px]">{kind}</Badge>;
            },
        },
        {
            id: 'status',
            header: 'Status',
            cell: ({ row }) => {
                const n = row.original;
                return n.paused
                    ? <span className="text-warning" title={n.pauseKind ?? undefined}>paused</span>
                    : n.success
                        ? <span className="text-success">ok</span>
                        : <span className="text-error" title={n.error ?? undefined}>failed</span>;
            },
        },
        {
            accessorKey: 'tokenCost',
            header: () => <span className="block text-right">Tokens</span>,
            cell: ({ getValue }) => <span className="block text-right text-(--theme-muted)">{(getValue() as number | null) ?? '—'}</span>,
        },
        {
            id: 'duration',
            header: () => <span className="block text-right">Duration</span>,
            cell: ({ row }) => {
                const n = row.original;
                return (
                    <span className="block text-right text-(--theme-muted) whitespace-nowrap">
                        {n.startedAt && n.endedAt
                            ? formatDuration(new Date(n.endedAt).getTime() - new Date(n.startedAt).getTime())
                            : '—'}
                    </span>
                );
            },
        },
    ], []);

    const columns = useMemo<ColumnDef<WorkflowRun, unknown>[]>(() => [
        {
            accessorKey: 'status',
            header: 'Status',
            cell: ({ getValue }) => <Badge variant={statusVariant(getValue() as WorkflowRunStatus)} className="text-xs">{getValue() as string}</Badge>,
        },
        {
            accessorKey: 'id',
            header: 'Run',
            enableSorting: false,
            cell: ({ getValue }) => {
                const v = getValue() as string;
                return <span className="font-mono text-xs" title={v}>{v.slice(0, 12)}…</span>;
            },
        },
        {
            accessorKey: 'sessionId',
            header: 'Session',
            enableSorting: false,
            cell: ({ getValue }) => {
                const s = getValue() as string | null;
                return s
                    ? <Link to={`/sessions/${s}`} className="font-mono text-xs hover:underline">{s.slice(0, 12)}…</Link>
                    : <span className="text-xs text-(--theme-muted)">—</span>;
            },
        },
        {
            id: 'step',
            header: 'Step',
            accessorFn: (row) => row.lastStepOrder,
            cell: ({ getValue }) => <div className="text-right text-xs">{(getValue() as number | null) ?? '—'}</div>,
        },
        {
            accessorKey: 'durationMs',
            header: 'Duration',
            cell: ({ getValue }) => <div className="text-right text-xs whitespace-nowrap">{formatDuration(getValue() as number | null)}</div>,
        },
        {
            accessorKey: 'createdAt',
            header: 'Started',
            cell: ({ getValue }) => <span className="text-xs text-(--theme-muted) whitespace-nowrap">{formatTimestamp(getValue() as string)}</span>,
        },
        {
            id: 'action',
            header: 'Action',
            enableSorting: false,
            cell: ({ row }) => {
                const run = row.original;
                return (
                    <div className="flex items-center justify-end gap-1">
                        {id && (
                            <Link to={`/workflows/${id}/runs/${run.id}/graph`}>
                                <Button
                                    variant="ghost"
                                    size="sm"
                                    className="gap-1 text-(--theme-muted) hover:text-info"
                                    title="View this run as a graph (live node status)"
                                >
                                    <LuWorkflow className="w-3 h-3" /> Graph
                                </Button>
                            </Link>
                        )}
                        <Button
                            variant="ghost"
                            size="sm"
                            className="gap-1 text-(--theme-muted) hover:text-info"
                            onClick={() => handleTraceOpen(run)}
                            title="View the per-node DAG execution trace"
                        >
                            <LuListTree className="w-3 h-3" /> Trace
                        </Button>
                        {run.status === 'PAUSED' && (
                            <Button
                                variant="ghost"
                                size="sm"
                                className="gap-1 text-info hover:text-primary"
                                onClick={() => handleResumeOpen(run)}
                                title="Resume this paused workflow run"
                            >
                                <LuPlay className="w-3 h-3" /> Resume
                            </Button>
                        )}
                        {run.status === 'AWAITING_ROUTE_SELECTION' && (
                            <Button
                                variant="ghost"
                                size="sm"
                                className="gap-1 text-info hover:text-primary"
                                onClick={() => handleRouteOpen(run)}
                                title="Choose the route for this paused run"
                            >
                                <LuGitBranch className="w-3 h-3" /> Choose route
                            </Button>
                        )}
                        {isCancellable(run.status) && (
                            <Button
                                variant="ghost"
                                size="sm"
                                className="gap-1 text-(--theme-muted) hover:text-error"
                                onClick={() => {
                                    setCancelTarget(run);
                                    setCancelError(null);
                                }}
                                title="Cancel this workflow run"
                            >
                                <LuBan className="w-3 h-3" /> Cancel
                            </Button>
                        )}
                        {run.status !== 'PAUSED' && !isCancellable(run.status) && (
                            <span className="text-(--theme-muted) text-xs">—</span>
                        )}
                    </div>
                );
            },
        },
    ], [id]);

    return (
        <PageContainer variant="dashboard">
            <PageHeader
                icon={LuWaypoints}
                title="Workflow Run History"
                subtitle={id ? `Workflow ${id}` : undefined}
                actions={
                    <>
                        {id && (
                            <Link to={`/workflows/${id}`}>
                                <Button variant="ghost" size="sm" className="gap-2">
                                    <LuArrowLeft className="w-4 h-4" />
                                    Back to editor
                                </Button>
                            </Link>
                        )}
                        <Button
                            variant="outline"
                            size="sm"
                            onClick={() => refetch()}
                            disabled={isFetching}
                            className="gap-2"
                        >
                            {isFetching
                                ? <span className="loading loading-spinner loading-sm" />
                                : <LuRefreshCw className="w-4 h-4" />}
                            Refresh
                        </Button>
                    </>
                }
            />

            <div className="text-xs text-(--theme-muted) -mt-2">
                {data ? `${data.page.totalElements.toLocaleString()} run${data.page.totalElements === 1 ? '' : 's'}` : 'Loading…'}
            </div>

            {resumeFeedback && (
                <Alert
                    severity="success"
                    description={resumeFeedback}
                    dismissible
                    onClose={() => setResumeFeedback(null)}
                />
            )}

            {cancelFeedback && (
                <Alert
                    severity="info"
                    description={cancelFeedback}
                    dismissible
                    onClose={() => setCancelFeedback(null)}
                />
            )}

            {routeFeedback && (
                <Alert
                    severity="success"
                    description={routeFeedback}
                    dismissible
                    onClose={() => setRouteFeedback(null)}
                />
            )}

            {error && (
                <Alert
                    severity="error"
                    title={(error as { status?: number }).status === 404 ? 'Workflow not found' : 'Failed to load workflow runs'}
                >
                    {(error as Error).message}
                </Alert>
            )}

            {isLoading && !data ? (
                <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl overflow-hidden p-4 space-y-2">
                    {[1, 2, 3, 4].map(i => (
                        <div key={i} className="h-10 bg-obsidian-elevated/50 rounded animate-pulse" />
                    ))}
                </div>
            ) : data ? (
                <DataTable
                    columns={columns}
                    data={data.content}
                    manualPagination
                    pageIndex={page}
                    pageSize={PAGE_SIZE}
                    totalElements={data.page.totalElements}
                    onPageChange={setPage}
                    emptyMessage="No runs yet for this workflow."
                />
            ) : null}

            {/* Resume modal — HITL output for a paused run */}
            <Dialog
                isOpen={resumeTarget !== null}
                setIsOpen={(open) => {
                    if (!open && !resuming) {
                        setResumeTarget(null);
                        setResumeError(null);
                    }
                }}
                title={resumeTarget ? `Resume run ${resumeTarget.id.slice(0, 8)}…` : 'Resume run'}
                severity="warning"
                confirmLabel={resuming ? 'Submitting…' : 'Resume'}
                cancelLabel="Cancel"
                shouldCloseOnConfirm={false}
                onConfirm={handleResumeSubmit}
                onCancel={() => {
                    if (!resuming) {
                        setResumeTarget(null);
                        setResumeError(null);
                    }
                }}
            >
                <div className="space-y-4 pt-2">
                    <p className="text-xs text-(--theme-muted)">
                        Provide the output a human reviewer would have produced for the paused step. The workflow resumes from the next step using this value.
                    </p>

                    <div>
                        <label className="label py-1">
                            <span className="text-xs font-bold text-(--theme-muted) uppercase tracking-wider">Output</span>
                            <span className="text-[10px] text-error">*</span>
                        </label>
                        <textarea
                            className="textarea textarea-bordered h-32 w-full text-sm font-mono"
                            value={resumeOutput}
                            onChange={(e) => setResumeOutput(e.target.value)}
                            placeholder="Step output to inject. Plain text; downstream steps may parse it as JSON if they expect that shape."
                            disabled={resuming}
                        />
                    </div>

                    {resumeTarget && (
                        <div className="text-[11px] text-(--theme-muted) font-mono">
                            Last step order: <span className="text-(--theme-foreground)">{resumeTarget.lastStepOrder ?? '—'}</span>
                            {resumeTarget.sessionId && (
                                <> · Session: <span className="text-(--theme-foreground)">{resumeTarget.sessionId.slice(0, 12)}…</span></>
                            )}
                        </div>
                    )}

                    {resumeError && (
                        <div className="text-xs text-error font-medium">{resumeError}</div>
                    )}
                </div>
            </Dialog>

            {/* Cancel confirmation — transitions a non-terminal run to CANCELLED */}
            <Dialog
                isOpen={cancelTarget !== null}
                setIsOpen={(open) => {
                    if (!open && !cancelling) {
                        setCancelTarget(null);
                        setCancelError(null);
                    }
                }}
                title={cancelTarget ? `Cancel run ${cancelTarget.id.slice(0, 8)}…?` : 'Cancel run'}
                severity="error"
                confirmLabel={cancelling ? 'Cancelling…' : 'Cancel run'}
                cancelLabel="Keep running"
                shouldCloseOnConfirm={false}
                onConfirm={handleCancelSubmit}
                onCancel={() => {
                    if (!cancelling) {
                        setCancelTarget(null);
                        setCancelError(null);
                    }
                }}
            >
                <div className="space-y-3 pt-2">
                    <p className="text-xs text-(--theme-muted)">
                        This transitions the run to <span className="font-mono text-(--theme-foreground)">CANCELLED</span> and
                        stops further step execution. Already-terminal runs are unaffected. This cannot be undone.
                    </p>
                    {cancelTarget && (
                        <div className="text-[11px] text-(--theme-muted) font-mono">
                            Status: <span className="text-(--theme-foreground)">{cancelTarget.status}</span>
                            {' · '}Last step order: <span className="text-(--theme-foreground)">{cancelTarget.lastStepOrder ?? '—'}</span>
                        </div>
                    )}
                    {cancelError && (
                        <div className="text-xs text-error font-medium">{cancelError}</div>
                    )}
                </div>
            </Dialog>

            {/* Router-choice picker — resumes a run paused at a ROUTER HITL gate */}
            <Dialog
                isOpen={routeTarget !== null}
                setIsOpen={(open) => {
                    if (!open && !continuing) {
                        setRouteTarget(null);
                        setRouteError(null);
                    }
                }}
                title={routeTarget ? `Choose route for run ${routeTarget.id.slice(0, 8)}…` : 'Choose route'}
                severity="warning"
                confirmLabel="Close"
                canBeCanceled={false}
                shouldCloseOnConfirm={false}
                onConfirm={() => { if (!continuing) setRouteTarget(null); }}
            >
                <div className="space-y-4 pt-2">
                    <p className="text-xs text-(--theme-muted)">
                        Select one of the router step's declared choices to resume the run down that branch.
                    </p>

                    {routeLoading ? (
                        <div className="flex items-center gap-2 text-xs text-(--theme-muted)">
                            <span className="loading loading-spinner loading-sm" /> Loading choices…
                        </div>
                    ) : routeChoices.length > 0 ? (
                        <div className="flex flex-wrap gap-2">
                            {routeChoices.map((choice) => (
                                <Button
                                    key={choice}
                                    variant={choice === routeDefault ? 'primary' : 'outline'}
                                    size="sm"
                                    className="gap-1"
                                    disabled={continuing}
                                    onClick={() => handleRouteSelect(choice)}
                                    title={choice === routeDefault ? 'Default choice' : undefined}
                                >
                                    <LuGitBranch className="w-3 h-3" />
                                    {choice}{choice === routeDefault ? ' (default)' : ''}
                                </Button>
                            ))}
                        </div>
                    ) : !routeError ? (
                        <div className="text-xs text-(--theme-muted)">No route choices available for this run.</div>
                    ) : null}

                    {routeError && (
                        <div className="text-xs text-error font-medium">{routeError}</div>
                    )}
                </div>
            </Dialog>

            {/* DAG node-run trace — what the frontier scheduler ran (empty for flat-engine runs) */}
            <Dialog
                isOpen={traceTarget !== null}
                setIsOpen={(open) => { if (!open) setTraceTarget(null); }}
                title={traceTarget ? `Node trace · run ${traceTarget.id.slice(0, 8)}…` : 'Node trace'}
                severity="info"
                confirmLabel="Close"
                canBeCanceled={false}
                onConfirm={() => setTraceTarget(null)}
            >
                <div className="space-y-3 pt-2">
                    {traceLoading ? (
                        <div className="flex items-center gap-2 text-xs text-(--theme-muted)">
                            <span className="loading loading-spinner loading-sm" /> Loading trace…
                        </div>
                    ) : traceError ? (
                        <div className="text-xs text-error font-medium">{traceError}</div>
                    ) : traceRows.length === 0 ? (
                        <div className="text-xs text-(--theme-muted)">
                            No per-node trace for this run — it ran on the flat <span className="font-mono">step_order</span> engine,
                            or the DAG engine is disabled for this workflow.
                        </div>
                    ) : (
                        <DataTable
                            columns={traceColumns}
                            data={traceRows}
                            enablePagination
                            defaultPageSize={10}
                            emptyMessage="No per-node trace for this run."
                        />
                    )}
                </div>
            </Dialog>
        </PageContainer>
    );
};
