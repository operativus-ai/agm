import React from 'react';
import { Link, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { LuArrowLeft, LuOctagonX, LuPlay } from 'react-icons/lu';

import { PageContainer } from '../../../shared/components/ui/PageContainer';
import { PageHeader } from '../../../shared/components/ui/PageHeader';
import { Tabs } from '../../../shared/components/ui/Tabs';
import { Badge } from '../../../shared/components/ui/Badge';
import { Alert } from '../../../shared/components/ui/Alert';
import { Button } from '../../../shared/components/ui/Button';
import { Dialog } from '../../../shared/components/ui/Dialog';

import { AgentsApi } from '../../agents/api/agents-api';
import { runsApi } from '../api/runsApi';
import { CostBreakdownTab } from '../components/CostBreakdownTab';
import { OrchestrationDecisionsTab } from '../components/OrchestrationDecisionsTab';
import { RunDelegationTab } from '../components/RunDelegationTab';
import { RunEventTimeline } from '../components/RunEventTimeline';
import { RunOverviewTab } from '../components/RunOverviewTab';
import { RunReflectionsTab } from '../components/RunReflectionsTab';
import { TERMINAL_RUN_STATUSES, type AgentRunResponse, type RunStatus } from '../types/runs';

const statusVariant = (s: RunStatus): 'success' | 'error' | 'warning' | 'info' | 'ghost' => {
    if (s === 'COMPLETED' || s === 'APPROVED') return 'success';
    if (s === 'FAILED' || s === 'REJECTED' || s === 'EXPIRED' || s === 'CANCELLED') return 'error';
    if (s === 'RUNNING' || s === 'PROCESSING') return 'info';
    if (s === 'PAUSED' || s === 'QUEUED' || s === 'PENDING') return 'warning';
    return 'ghost';
};

const TabStub: React.FC<{ label: string; message: string }> = ({ label, message }) => (
    <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl p-8 text-center text-(--theme-muted)">
        <div className="font-medium text-(--theme-foreground)">{label}</div>
        <div className="mt-2 text-sm">{message}</div>
    </div>
);

const TAB_DEFS: ReadonlyArray<{ slug: string; label: string; render: (run: AgentRunResponse) => React.ReactNode }> = [
    { slug: 'overview', label: 'Overview', render: (run) => <RunOverviewTab run={run} /> },
    { slug: 'events', label: 'Events', render: (run) => <RunEventTimeline runId={run.id} initialStatusIsTerminal={TERMINAL_RUN_STATUSES.has(run.status)} /> },
    { slug: 'decisions', label: 'Decisions', render: (run) => <OrchestrationDecisionsTab runId={run.id} /> },
    { slug: 'reflections', label: 'Reflections', render: (run) => <RunReflectionsTab runId={run.id} /> },
    { slug: 'cost', label: 'Cost', render: (run) => <CostBreakdownTab runId={run.id} /> },
    { slug: 'delegation', label: 'Delegation', render: (run) => <RunDelegationTab runId={run.id} /> },
];

export const RunDetailPage: React.FC = () => {
    const { runId } = useParams<{ runId: string }>();
    const queryClient = useQueryClient();
    const [cancelDialogOpen, setCancelDialogOpen] = React.useState(false);
    const [cancelError, setCancelError] = React.useState<string | null>(null);

    const { data: run, isLoading, error } = useQuery({
        queryKey: ['runs', 'detail', runId],
        queryFn: () => runsApi.get(runId!),
        enabled: Boolean(runId),
        staleTime: 30_000,
    });

    /** §4 T045 — operator-fired per-run cancellation. Backend transitions the run to
     *  CANCELLED and interrupts the executing virtual thread; we invalidate the cached
     *  detail query so the badge + downstream tabs (Events, Decisions, Cost) refresh. */
    const cancelMutation = useMutation({
        mutationFn: ({ agentId, runId: id }: { agentId: string; runId: string }) =>
            AgentsApi.cancelRun(agentId, id),
        onSuccess: () => {
            setCancelError(null);
            setCancelDialogOpen(false);
            queryClient.invalidateQueries({ queryKey: ['runs', 'detail', runId] });
            queryClient.invalidateQueries({ queryKey: ['runs', 'list'] });
        },
        onError: (err: Error) => {
            setCancelError(err.message || 'Failed to cancel run');
        },
    });

    const isCancellable = run !== undefined
        && Boolean(run.agentId)
        && !TERMINAL_RUN_STATUSES.has(run.status);

    return (
        <PageContainer variant="dashboard">
            <PageHeader
                icon={LuPlay}
                title={runId ? `Run ${runId}` : 'Run'}
                actions={
                    <div className="flex items-center gap-2">
                        {isCancellable && (
                            <Button
                                variant="ghost"
                                size="sm"
                                className="gap-2 text-error hover:bg-error/10"
                                onClick={() => { setCancelError(null); setCancelDialogOpen(true); }}
                                disabled={cancelMutation.isPending}
                                title="Stop this run and mark it CANCELLED"
                            >
                                <LuOctagonX className="w-4 h-4" />
                                {cancelMutation.isPending ? 'Cancelling…' : 'Cancel run'}
                            </Button>
                        )}
                        <Link to="/runs">
                            <Button variant="ghost" size="sm" className="gap-2">
                                <LuArrowLeft className="w-4 h-4" />
                                Back to runs
                            </Button>
                        </Link>
                    </div>
                }
            />

            <div className="flex items-center gap-3 text-xs text-(--theme-muted) -mt-2">
                {run ? (
                    <>
                        <Badge variant={statusVariant(run.status)} className="text-xs">{run.status}</Badge>
                        {run.agentId && <span className="font-mono">agent={run.agentId}</span>}
                        {run.model && <span className="font-mono">model={run.model}</span>}
                    </>
                ) : isLoading ? (
                    <span>Loading run details…</span>
                ) : (
                    <span>Run details unavailable</span>
                )}
            </div>

            {error && (
                <Alert severity="error" title={(error as { status?: number }).status === 404 ? 'Run not found' : 'Failed to load run'}>
                    {(error as Error).message}
                </Alert>
            )}

            {cancelError && (
                <Alert severity="error" title="Cancel failed">{cancelError}</Alert>
            )}

            <Dialog
                isOpen={cancelDialogOpen}
                setIsOpen={setCancelDialogOpen}
                title="Cancel run?"
                content={
                    run
                        ? `Run ${run.id} is currently ${run.status}. Cancelling will stop the executing thread and mark the run as CANCELLED. This cannot be undone.`
                        : 'Cancel this run?'
                }
                severity="error"
                confirmLabel={cancelMutation.isPending ? 'Cancelling…' : 'Cancel run'}
                cancelLabel="Keep running"
                onConfirm={() => {
                    if (run?.agentId && run.id) {
                        cancelMutation.mutate({ agentId: run.agentId, runId: run.id });
                    }
                }}
                onCancel={() => setCancelDialogOpen(false)}
            />

            <Tabs defaultValue="overview">
                <Tabs.List>
                    {TAB_DEFS.map(t => (
                        <Tabs.Trigger key={t.slug} value={t.slug}>{t.label}</Tabs.Trigger>
                    ))}
                </Tabs.List>
                {TAB_DEFS.map(t => (
                    <Tabs.Content key={t.slug} value={t.slug}>
                        {run ? t.render(run) : <TabStub label={t.label} message={isLoading ? 'Loading…' : 'Run details unavailable'} />}
                    </Tabs.Content>
                ))}
            </Tabs>
        </PageContainer>
    );
};
