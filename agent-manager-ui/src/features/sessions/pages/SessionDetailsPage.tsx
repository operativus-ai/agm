import React, { useEffect, useState } from 'react';
import { Link, useParams, useNavigate } from 'react-router-dom';
import { PageHeader } from '../../../shared/components/ui/PageHeader';
import { sessionApi } from '../api/sessionApi';
import type { AgentSession, AgentRun } from '../api/sessionApi';
import { TraceVisualizer } from '../../observability/components/TraceVisualizer';
import { logger } from '../../../utils/logger';
import { RunStatus } from '../../../shared/types/enums';
import { PageContainer } from '../../../shared/components/ui/PageContainer';
import { runsApi } from '../../runs/api/runsApi';
import { Button } from '../../../shared/components/ui/Button';
import { LuBan } from 'react-icons/lu';

export const SessionDetailsPage: React.FC = () => {
    const { id } = useParams<{ id: string }>();
    const navigate = useNavigate();
    
    const [session, setSession] = useState<AgentSession | null>(null);
    const [runs, setRuns] = useState<AgentRun[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [cancellingRunId, setCancellingRunId] = useState<string | null>(null);

    useEffect(() => {
        if (id) {
            loadSessionData(id);
        }
    }, [id]);

    const handleCancelRun = async (runId: string) => {
        if (!confirm('Cancel this run?')) return;
        try {
            setCancellingRunId(runId);
            // BE PR #973: user-side cancel. Non-admin users can cancel their own
            // runs. The admin-gated counterpart at AgentAdminApi.cancelRun is
            // unsuitable for SessionDetailsPage since this page is not an admin route.
            await runsApi.cancel(runId);
            if (id) loadSessionData(id);
        } catch (err) {
            logger.error('Cancel run failed', err);
        } finally {
            setCancellingRunId(null);
        }
    };

    const loadSessionData = async (sessionId: string) => {
        try {
            setLoading(true);
            const [sessionData, runsData] = await Promise.all([
                sessionApi.getSession(sessionId).catch(() => null),
                sessionApi.getSessionRuns(sessionId).catch(() => [])
            ]);
            
            if (!sessionData) {
                setError("Session not found");
            } else {
                setSession(sessionData);
                setRuns(runsData || []);
            }
        } catch (err: any) {
            logger.error("Failed to load session details", err);
            setError(err.message || "Failed to load details");
        } finally {
            setLoading(false);
        }
    };

    if (loading) {
        return (
            <div className="flex justify-center items-center h-64">
                <span className="loading loading-spinner text-primary loading-lg"></span>
            </div>
        );
    }

    if (error || !session) {
        return (
            <div className="alert alert-error max-w-2xl mx-auto mt-8">
                <span>{error || 'Session not found'}</span>
                <button className="btn btn-sm" onClick={() => navigate('/sessions')}>Back</button>
            </div>
        );
    }

    return (
        <PageContainer>
            <button className="btn btn-sm btn-ghost pl-0 mb-2" onClick={() => navigate('/sessions')}>
                ← Back to Sessions
            </button>
            <PageHeader
                title="Session Details"
                subtitle={session.id}
            />

            <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
                <div className="card bg-obsidian-surface shadow-xl border border-obsidian-stroke md:col-span-1 h-fit">
                    <div className="card-body">
                        <h2 className="card-title text-primary border-b border-obsidian-stroke pb-2">Overview</h2>
                        <div className="space-y-4 mt-2">
                            <div>
                                <div className="text-xs text-theme-muted uppercase font-bold tracking-wider">Status</div>
                                <span className={`badge mt-1 ${session.status === 'ACTIVE' ? 'badge-success' : 'badge-ghost'}`}>
                                    {session.status || 'UNKNOWN'}
                                </span>
                            </div>
                            <div>
                                <div className="text-xs text-theme-muted uppercase font-bold tracking-wider">User ID</div>
                                <div>{session.userId}</div>
                            </div>
                            <div>
                                <div className="text-xs text-theme-muted uppercase font-bold tracking-wider">Agent / Team</div>
                                <div>{session.agentId}</div>
                            </div>
                            <div>
                                <div className="text-xs text-theme-muted uppercase font-bold tracking-wider">Created At</div>
                                <div>{new Date(session.createdAt).toLocaleString()}</div>
                            </div>
                            <div>
                                <div className="text-xs text-theme-muted uppercase font-bold tracking-wider">Updated At</div>
                                <div>{new Date(session.updatedAt).toLocaleString()}</div>
                            </div>
                        </div>
                    </div>
                </div>

                <div className="card bg-obsidian-surface shadow-xl border border-obsidian-stroke md:col-span-2">
                    <div className="card-body">
                        <h2 className="card-title text-primary border-b border-obsidian-stroke pb-2">Run History</h2>
                        
                        {runs.length === 0 ? (
                            <div className="text-center py-8 text-theme-muted">
                                No runs found for this session.
                            </div>
                        ) : (
                            <div className="space-y-4 mt-4">
                                {runs.map((run) => (
                                    <div key={run.id} className="border border-obsidian-stroke rounded-lg p-4 bg-obsidian-elevated/30">
                                        <div className="flex justify-between items-start mb-2">
                                            <Link
                                                to={`/runs/${run.id}`}
                                                className="font-mono text-sm font-bold hover:underline"
                                                title="Open run details"
                                            >
                                                {run.id}
                                            </Link>
                                            <div className="flex items-center gap-2">
                                                <span className={`badge ${run.status === RunStatus.COMPLETED ? 'badge-success' : run.status === RunStatus.FAILED ? 'badge-error' : run.status === RunStatus.CANCELLED ? 'badge-ghost' : 'badge-secondary'}`}>
                                                    {run.status}
                                                </span>
                                                {(run.status === RunStatus.RUNNING || run.status === RunStatus.PENDING || run.status === RunStatus.QUEUED) && (
                                                    <Button
                                                        size="sm"
                                                        variant="ghost"
                                                        className="gap-1 text-error hover:bg-error/10 px-2"
                                                        disabled={cancellingRunId === run.id}
                                                        onClick={() => handleCancelRun(run.id)}
                                                        title="Cancel run"
                                                    >
                                                        <LuBan className="w-3.5 h-3.5" />
                                                        {cancellingRunId === run.id ? '…' : 'Cancel'}
                                                    </Button>
                                                )}
                                            </div>
                                        </div>
                                        <div className="text-xs text-theme-muted flex gap-4 mb-2">
                                            <span>Start: {new Date(run.startedAt).toLocaleString()}</span>
                                            {run.completedAt && <span>End: {new Date(run.completedAt).toLocaleString()}</span>}
                                        </div>
                                        {run.logOutput && (
                                            <div className="mt-2 text-xs font-mono bg-obsidian-elevated p-2 rounded-md overflow-x-auto">
                                                {run.logOutput}
                                            </div>
                                        )}
                                        <div className="mt-4 border-t border-base-300 pt-4">
                                            <TraceVisualizer runId={run.id} />
                                        </div>
                                    </div>
                                ))}
                            </div>
                        )}
                    </div>
                </div>
            </div>
        </PageContainer>
    );
};
