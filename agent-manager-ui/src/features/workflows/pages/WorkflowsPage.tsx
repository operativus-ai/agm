import React, { useEffect, useMemo, useState } from 'react';
import { orchestrationApi } from '../../../shared/api/orchestrationApi';
import type { Workflow } from '../../../shared/types/orchestration';
import type { ColumnDef } from '@tanstack/react-table';
import { PageHeader } from '../../../shared/components/ui/PageHeader';
import { Button } from '../../../shared/components/ui/Button';
import { Badge } from '../../../shared/components/ui/Badge';
import { DataTable } from '../../../shared/components/ui/DataTable';
import { Dialog } from '../../../shared/components/ui/Dialog';
import { Alert } from '../../../shared/components/ui/Alert';
import { useNavigate } from 'react-router-dom';
import {
    LuWaypoints, LuPlus, LuSettings, LuHistory, LuCopy, LuTrash2, LuSearch, LuPlay,
} from 'react-icons/lu';
import { PageContainer } from '../../../shared/components/ui/PageContainer';
import { RunStatus } from '../../../shared/types/enums';

export const WorkflowsPage: React.FC = () => {
  const [workflows, setWorkflows] = useState<Workflow[]>([]);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState('');
  const navigate = useNavigate();

  // Pagination
  const PAGE_SIZE = 20;
  const [pageIndex, setPageIndex] = useState(0);
  const [totalElements, setTotalElements] = useState(0);

  // Feedback
  const [feedback, setFeedback] = useState<{ type: 'success' | 'error'; message: string } | null>(null);

  // Delete confirmation
  const [deleteTarget, setDeleteTarget] = useState<Workflow | null>(null);
  const [deleteOpen, setDeleteOpen] = useState(false);

  // Run-workflow modal
  const [runTarget, setRunTarget] = useState<Workflow | null>(null);
  const [runInput, setRunInput] = useState('');
  const [runSessionId, setRunSessionId] = useState('');
  const [running, setRunning] = useState(false);
  const [runError, setRunError] = useState<string | null>(null);

  useEffect(() => {
    loadWorkflows();
  }, [pageIndex]);

  const loadWorkflows = async () => {
    try {
      setLoading(true);
      const data = await orchestrationApi.getWorkflows({ page: pageIndex, size: PAGE_SIZE });
      if (data && data.content) {
        setWorkflows(data.content);
        setTotalElements(data.page.totalElements);
      } else {
        setWorkflows([]);
        setTotalElements(0);
      }
    } catch (err) {
      console.error('Failed to load workflows', err);
    } finally {
      setLoading(false);
    }
  };

  const handleClone = async (wf: Workflow) => {
    try {
      setFeedback(null);
      const cloned = await orchestrationApi.cloneWorkflow(wf.id);
      setFeedback({ type: 'success', message: `"${wf.name}" cloned successfully.` });
      navigate(`/workflows/${cloned.id}`);
    } catch (err) {
      console.error('Failed to clone workflow', err);
      setFeedback({ type: 'error', message: `Failed to clone "${wf.name}".` });
    }
  };

  const handleRunOpen = (wf: Workflow) => {
    setRunTarget(wf);
    setRunInput('');
    setRunSessionId('');
    setRunError(null);
  };

  const handleRunSubmit = async () => {
    if (!runTarget) return;
    if (!runInput.trim()) {
      setRunError('Input is required.');
      return;
    }
    setRunning(true);
    setRunError(null);
    try {
      const res = await orchestrationApi.runWorkflow(
        runTarget.id,
        runInput.trim(),
        runSessionId.trim() || undefined,
      );
      setFeedback({
        type: 'success',
        message: `"${runTarget.name}" queued (job ${res.jobId.slice(0, 8)}…, session ${res.sessionId.slice(0, 8)}…). Opening run history.`,
      });
      const id = runTarget.id;
      setRunTarget(null);
      navigate(`/workflows/${id}/runs`);
    } catch (err: any) {
      setRunError(err?.message || 'Workflow run submission failed.');
    } finally {
      setRunning(false);
    }
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    try {
      setFeedback(null);
      await orchestrationApi.deleteWorkflow(deleteTarget.id);
      setFeedback({ type: 'success', message: `"${deleteTarget.name}" deleted.` });
      setDeleteTarget(null);
      setDeleteOpen(false);
      loadWorkflows();
    } catch (err) {
      console.error('Failed to delete workflow', err);
      setFeedback({ type: 'error', message: `Failed to delete "${deleteTarget.name}".` });
      setDeleteOpen(false);
    }
  };

  // Client-side search filter
  const filteredWorkflows = useMemo(() => {
    if (!search.trim()) return workflows;
    const q = search.toLowerCase();
    return workflows.filter(
      wf => wf.name?.toLowerCase().includes(q) || wf.description?.toLowerCase().includes(q)
    );
  }, [workflows, search]);

  const columns = useMemo<ColumnDef<Workflow, unknown>[]>(() => [
    {
      accessorKey: 'name',
      header: 'Workflow',
      cell: ({ row }) => {
        const wf = row.original;
        return (
          <div className="flex items-center gap-3 min-w-0">
            <LuWaypoints className="w-4 h-4 shrink-0 text-(--theme-muted)" />
            <div className="min-w-0">
              <span className="font-medium text-sm text-info truncate block max-w-50" title={wf.name}>
                {wf.name}
              </span>
              {wf.description && (
                <span className="text-xs text-(--theme-muted) truncate block max-w-50">
                  {wf.description}
                </span>
              )}
            </div>
          </div>
        );
      },
    },
    {
      accessorKey: 'status',
      header: 'Status',
      cell: ({ getValue }) => {
        const status = getValue() as string;
        const variant =
          status === RunStatus.RUNNING ? 'primary'
          : status === RunStatus.COMPLETED ? 'success'
          : status === RunStatus.FAILED ? 'error'
          : status === RunStatus.PAUSED ? 'warning'
          : 'neutral';
        const animate = status === RunStatus.RUNNING ? 'animate-pulse' : '';
        return <Badge variant={variant} outline className={`text-xs ${animate}`}>{status || 'DRAFT'}</Badge>;
      },
    },
    {
      id: 'steps',
      header: 'Steps',
      accessorFn: (row) => row.stepCount ?? row.steps?.length ?? 0,
      cell: ({ getValue }) => (
        <span className="font-mono bg-obsidian-elevated px-2 py-0.5 rounded text-xs">{getValue() as number}</span>
      ),
    },
    {
      accessorKey: 'updatedAt',
      header: 'Updated',
      cell: ({ getValue }) => (
        <span className="text-xs text-(--theme-muted) whitespace-nowrap">
          {new Date(getValue() as string).toLocaleString()}
        </span>
      ),
    },
    {
      id: 'actions',
      header: '',
      enableSorting: false,
      cell: ({ row }) => {
        const wf = row.original;
        return (
          <div className="flex items-center justify-end gap-1" onClick={(e) => e.stopPropagation()}>
            <button
              type="button"
              onClick={() => handleRunOpen(wf)}
              aria-label="Run workflow"
              title="Run"
              className="btn btn-ghost btn-sm btn-square rounded-md text-(--theme-muted) hover:text-info"
            >
              <LuPlay className="w-4 h-4" />
            </button>
            <button
              type="button"
              onClick={() => navigate(`/workflows/${wf.id}`)}
              aria-label="Configure workflow"
              title="Configure"
              className="btn btn-ghost btn-sm btn-square rounded-md text-(--theme-muted) hover:text-(--theme-foreground)"
            >
              <LuSettings className="w-4 h-4" />
            </button>
            <button
              type="button"
              onClick={() => navigate(`/workflows/${wf.id}/runs`)}
              aria-label="Run history"
              title="Run History"
              className="btn btn-ghost btn-sm btn-square rounded-md text-(--theme-muted) hover:text-(--theme-foreground)"
            >
              <LuHistory className="w-4 h-4" />
            </button>
            <button
              type="button"
              onClick={() => handleClone(wf)}
              aria-label="Clone workflow"
              title="Clone"
              className="btn btn-ghost btn-sm btn-square rounded-md text-(--theme-muted) hover:text-(--theme-foreground)"
            >
              <LuCopy className="w-4 h-4" />
            </button>
            <button
              type="button"
              onClick={() => { setDeleteTarget(wf); setDeleteOpen(true); }}
              aria-label="Delete workflow"
              title="Delete"
              className="btn btn-ghost btn-sm btn-square rounded-md text-(--theme-muted) hover:text-error"
            >
              <LuTrash2 className="w-4 h-4" />
            </button>
          </div>
        );
      },
    },
  ], []);

  return (
    <PageContainer variant="dashboard">
      <PageHeader
        icon={LuWaypoints}
        title="Workflows"
        subtitle="Construct and monitor long-running, multi-step agent sequences."
        actions={
          <Button size="sm" onClick={() => navigate('/workflows/new')} className="gap-1.5">
            <LuPlus className="w-4 h-4" /> Create Workflow
          </Button>
        }
      />

      {/* Feedback */}
      {feedback && (
        <Alert
          severity={feedback.type === 'success' ? 'success' : 'error'}
          description={feedback.message}
          dismissible
          onClose={() => setFeedback(null)}
        />
      )}

      {/* Search */}
      <div className="relative max-w-sm">
        <LuSearch className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-(--theme-muted)" />
        <input
          type="text"
          placeholder="Search workflows..."
          className="input input-bordered input-sm w-full pl-9"
          value={search}
          onChange={e => setSearch(e.target.value)}
        />
      </div>

      {loading ? (
        <div className="space-y-2">
          {[1, 2, 3].map(i => <div key={i} className="h-12 bg-obsidian-elevated/50 rounded-md animate-pulse" />)}
        </div>
      ) : (
        <DataTable
          columns={columns}
          data={filteredWorkflows}
          manualPagination
          pageIndex={pageIndex}
          pageSize={PAGE_SIZE}
          totalElements={search.trim() ? filteredWorkflows.length : totalElements}
          onPageChange={setPageIndex}
          onRowClick={(wf) => navigate(`/workflows/${wf.id}`)}
          emptyMessage="No workflows found. Create one to chain multi-step agent sequences."
        />
      )}

      {/* Delete Confirmation */}
      <Dialog
        isOpen={deleteOpen}
        setIsOpen={setDeleteOpen}
        title="Delete Workflow"
        content={`Are you sure you want to delete "${deleteTarget?.name}"? This will remove all steps and cannot be undone.`}
        severity="error"
        confirmLabel="Delete"
        onConfirm={handleDelete}
        onCancel={() => setDeleteTarget(null)}
      />

      {/* Run Workflow Modal */}
      <Dialog
        isOpen={runTarget !== null}
        setIsOpen={(open) => {
          if (!open && !running) {
            setRunTarget(null);
            setRunError(null);
          }
        }}
        title={runTarget ? `Run "${runTarget.name}"` : 'Run Workflow'}
        severity="info"
        confirmLabel={running ? 'Submitting…' : 'Run'}
        cancelLabel="Cancel"
        shouldCloseOnConfirm={false}
        onConfirm={handleRunSubmit}
        onCancel={() => {
          if (!running) {
            setRunTarget(null);
            setRunError(null);
          }
        }}
      >
        <div className="space-y-4 pt-2">
          <p className="text-xs text-(--theme-muted)">
            Queues a workflow execution job. You'll be redirected to the run history once the job is submitted.
          </p>

          <div>
            <label className="label py-1">
              <span className="text-xs font-bold text-(--theme-muted) uppercase tracking-wider">Input</span>
              <span className="text-[10px] text-error">*</span>
            </label>
            <textarea
              className="textarea textarea-bordered h-28 w-full text-sm font-mono"
              value={runInput}
              onChange={(e) => setRunInput(e.target.value)}
              placeholder="Initial input passed to the first step. Treat as plain text — workflow steps may parse it as JSON if they expect that."
              disabled={running}
            />
          </div>

          <div>
            <label className="label py-1">
              <span className="text-xs font-bold text-(--theme-muted) uppercase tracking-wider">Session ID</span>
              <span className="text-[10px] text-(--theme-muted) italic">optional</span>
            </label>
            <input
              type="text"
              className="input input-bordered input-sm w-full font-mono text-xs"
              value={runSessionId}
              onChange={(e) => setRunSessionId(e.target.value)}
              placeholder="Leave blank to auto-generate. Reuse an existing session to chain context."
              disabled={running}
            />
          </div>

          {runError && (
            <div className="text-xs text-error font-medium">{runError}</div>
          )}
        </div>
      </Dialog>
    </PageContainer>
  );
};
