import React, { useEffect, useState } from 'react';
import { useBackgroundRunStore } from '../store/backgroundRunStore';
import { RunStatus } from '../../../shared/types/enums';
import { AgentsApi } from '../api/agents-api';
import { logger } from '../../../utils/logger';
import { LuListTodo, LuLoader, LuX, LuCheck, LuTriangleAlert } from 'react-icons/lu';

export const ActiveRunsTracker: React.FC = () => {
  const { runs, updateRunStatus, removeRun, getActiveRuns } = useBackgroundRunStore();
  const [isOpen, setIsOpen] = useState(false);
  const activeRuns = getActiveRuns();

  useEffect(() => {
    // Poll active runs every 3 seconds. Batched by agentId to avoid the
    // N+1 fan-out the previous forEach pattern produced (10 runs = 200 req/min).
    if (activeRuns.length === 0) return;

    const interval = setInterval(async () => {
        // Group active runIds by agentId
        const byAgent = new Map<string, string[]>();
        for (const run of activeRuns) {
            const list = byAgent.get(run.agentId) ?? [];
            list.push(run.runId);
            byAgent.set(run.agentId, list);
        }

        const returnedRunIds = new Set<string>();

        // One batch call per distinct agentId
        await Promise.all(Array.from(byAgent.entries()).map(async ([agentId, runIds]) => {
            try {
                const statuses = await AgentsApi.getRunStatusBatch(agentId, runIds);
                for (const s of statuses) {
                    if (!s?.id) continue;
                    returnedRunIds.add(s.id);
                    const existing = activeRuns.find(r => r.runId === s.id);
                    if (existing && s.status && s.status !== existing.status) {
                        updateRunStatus(s.id, s.status);
                    }
                }
            } catch (err) {
                logger.error(`Batch status poll failed for agent ${agentId}`, err);
            }
        }));

        // Any run we polled for that wasn't in the response is gone server-side
        // (purged, not persisted) — remove it locally, same semantics the old
        // per-run 404 branch provided.
        for (const run of activeRuns) {
            if (!returnedRunIds.has(run.runId)) {
                removeRun(run.runId);
            }
        }
    }, 3000);

    return () => clearInterval(interval);
  }, [activeRuns, updateRunStatus, removeRun]);

  const handleCancel = async (e: React.MouseEvent, agentId: string, runId: string) => {
      e.stopPropagation();
      try {
          await AgentsApi.cancelRun(agentId, runId);
          updateRunStatus(runId, RunStatus.CANCELLED);
      } catch (err) {
          logger.error("Failed to cancel run", err);
      }
  };

  const handleDismiss = (e: React.MouseEvent, runId: string) => {
      e.stopPropagation();
      removeRun(runId);
  };

  const hasActive = activeRuns.length > 0;

  return (
    <div className="relative z-50">
      <button 
        className={`btn btn-circle btn-sm ${hasActive ? 'btn-primary animate-pulse' : 'btn-ghost'}`}
        onClick={() => setIsOpen(!isOpen)}
        title="Background Tasks"
      >
        <LuListTodo className={hasActive ? 'text-primary-content' : 'text-base-content/70'} />
        {hasActive && (
          <span className="absolute top-0 right-0 w-3 h-3 bg-error rounded-full border-2 border-(--theme-card) mix-blend-screen" />
        )}
      </button>

      {isOpen && (
        <div className="absolute top-12 right-0 w-80 bg-base-100 rounded-box shadow-xl border border-base-200 overflow-hidden text-sm">
            <div className="p-3 bg-obsidian-elevated font-bold border-b border-obsidian-stroke flex justify-between items-center">
                <span>Background Tasks</span>
                <span className="badge badge-sm">{runs.length}</span>
            </div>
            
            <div className="max-h-96 overflow-y-auto">
                {runs.length === 0 ? (
                    <div className="p-6 text-center text-base-content/50 opacity-70">
                        No recent background tasks.
                    </div>
                ) : (
                    <div className="divide-y divide-base-200">
                        {[...runs].reverse().map(run => {
                            const isActive = ([RunStatus.PENDING, RunStatus.RUNNING] as RunStatus[]).includes(run.status);
                            const isFailed = run.status === RunStatus.FAILED;
                            const isSuccess = run.status === RunStatus.COMPLETED;
                            
                            return (
                                <div key={run.runId} className={`p-3 hover:bg-obsidian-elevated/50 transition-colors ${isActive ? 'bg-primary/5' : ''}`}>
                                    <div className="flex justify-between items-start mb-1">
                                        <div className="font-semibold truncate pr-2" title={run.agentName}>{run.agentName}</div>
                                        <div className="flex items-center gap-2">
                                            {isActive && (
                                                <button 
                                                    onClick={(e) => handleCancel(e, run.agentId, run.runId)}
                                                    className="text-error hover:text-error/80 p-1"
                                                    title="Cancel Task"
                                                >
                                                    <LuX size={12} />
                                                </button>
                                            )}
                                            {!isActive && (
                                                <button 
                                                    onClick={(e) => handleDismiss(e, run.runId)}
                                                    className="text-base-content/50 hover:text-base-content p-1"
                                                    title="Dismiss"
                                                >
                                                    <LuX size={12} />
                                                </button>
                                            )}
                                        </div>
                                    </div>
                                    
                                    <div className="flex items-center justify-between text-xs">
                                        <div className="flex items-center gap-1.5 font-mono">
                                            {isActive && <LuLoader className="animate-spin text-primary" size={10} />}
                                            {isSuccess && <LuCheck className="text-success" size={10} />}
                                            {isFailed && <LuTriangleAlert className="text-error" size={10} />}
                                            {!isActive && !isSuccess && !isFailed && <span className="w-2.5 h-2.5 rounded-full bg-warning/50 border border-warning"></span>}
                                            <span className={`${isSuccess ? 'text-success' : isFailed ? 'text-error' : isActive ? 'text-primary' : 'text-base-content/70'}`}>
                                                {run.status}
                                            </span>
                                        </div>
                                        <div className="text-base-content/50" title={`ID: ${run.runId}`}>
                                            {new Date(run.startedAt).toLocaleTimeString()}
                                        </div>
                                    </div>
                                </div>
                            );
                        })}
                    </div>
                )}
            </div>
        </div>
      )}
      
      {/* Click-away backdrop */}
      {isOpen && (
          <div className="fixed inset-0 z-40 bg-transparent" onClick={() => setIsOpen(false)} />
      )}
    </div>
  );
};
