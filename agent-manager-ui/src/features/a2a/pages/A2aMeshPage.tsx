import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { ColumnDef } from '@tanstack/react-table';
import { usePeers, useCards, useDeregisterPeer } from '../hooks/useA2a';
import { A2aApi, isTerminalA2aStatus } from '../api/a2aApi';
import type { RemoteAgentRegistration, AgentCard, A2aTaskStatusEvent } from '../api/a2aApi';
import { PeerRegistrationModal } from '../components/PeerRegistrationModal';
import { AgentCardViewer } from '../components/AgentCardViewer';
import { A2aTaskSubmitPanel } from '../components/A2aTaskSubmitPanel';
import { A2aActiveTasksPanel, type ActiveA2aTask } from '../components/A2aActiveTasksPanel';
import { Typography } from '../../../shared/components/ui/Typography';
import { Alert } from '../../../shared/components/ui/Alert';
import { Button } from '../../../shared/components/ui/Button';
import { Badge } from '../../../shared/components/ui/Badge';
import { DataTable } from '../../../shared/components/ui/DataTable';
import { PageHeader } from '../../../shared/components/ui/PageHeader';
import {
  LuPlus, LuTrash2, LuGlobe, LuShield, LuEye, LuRefreshCw,
} from 'react-icons/lu';
import { PageContainer } from '../../../shared/components/ui/PageContainer';

export const A2aMeshPage: React.FC = () => {
  const { data: peers = [], isLoading: peersLoading, error: peersError, refetch: refetchPeers } = usePeers();
  const { data: cards = [], isLoading: cardsLoading, error: cardsError, refetch: refetchCards } = useCards();
  const deregisterPeer = useDeregisterPeer();

  const [isRegisterOpen, setIsRegisterOpen] = useState(false);
  const [selectedCardAgent, setSelectedCardAgent] = useState<{ id: string; name: string } | null>(null);

  // Active in-page A2A tasks. Keyed by client-pregenerated taskId so cancel
  // works from the moment of submit (no SSE-event race).
  const [activeTasks, setActiveTasks] = useState<ActiveA2aTask[]>([]);
  // AbortControllers for the SSE streams keyed by taskId. Held in a ref to
  // avoid re-rendering the page whenever a new task connects.
  const streamControllers = useRef<Map<string, AbortController>>(new Map());

  // Re-render the active-tasks panel periodically so "Xs ago" stays fresh.
  // 5s cadence is enough — task lifecycles are seconds-to-minutes.
  const [, setTick] = useState(0);
  useEffect(() => {
    if (activeTasks.length === 0) return;
    const interval = setInterval(() => setTick(t => t + 1), 5_000);
    return () => clearInterval(interval);
  }, [activeTasks.length]);

  // Tear down every open stream when the page unmounts.
  useEffect(() => {
    return () => {
      streamControllers.current.forEach(ctrl => ctrl.abort());
      streamControllers.current.clear();
    };
  }, []);

  const applyStatusEvent = useCallback((event: A2aTaskStatusEvent) => {
    setActiveTasks(prev => prev.map(t => {
      if (t.taskId !== event.taskId) return t;
      return {
        ...t,
        status: event.status,
        lastMessage: event.message,
        errorDetail: event.errorDetail,
        runId: event.runId ?? t.runId,
        updatedAt: Date.now(),
        cancelling: isTerminalA2aStatus(event.status) ? false : t.cancelling,
      };
    }));
  }, []);

  const markStreamClosed = useCallback((taskId: string) => {
    streamControllers.current.delete(taskId);
    setActiveTasks(prev => prev.map(t => {
      if (t.taskId !== taskId) return t;
      // If we never received a terminal SSE event (e.g. server crashed), mark
      // FAILED locally so the row doesn't stay "WORKING" forever.
      const stuck = !(t.status !== 'CONNECTING' && isTerminalA2aStatus(t.status)) && t.status !== 'CONNECTING';
      if (stuck) {
        return {
          ...t,
          status: 'FAILED',
          errorDetail: t.errorDetail ?? 'Stream closed before a terminal status event arrived.',
          updatedAt: Date.now(),
          cancelling: false,
        };
      }
      return t;
    }));
  }, []);

  const handleTaskSubmit = useCallback(async ({ targetAgentId, alias, input }: { targetAgentId: string; alias: string; input: string }) => {
    const taskId = crypto.randomUUID();
    const row: ActiveA2aTask = {
      taskId,
      alias,
      targetAgentId,
      input,
      status: 'CONNECTING',
      lastMessage: null,
      errorDetail: null,
      runId: null,
      startedAt: Date.now(),
      updatedAt: Date.now(),
      cancelling: false,
    };
    setActiveTasks(prev => [row, ...prev]);

    const controller = A2aApi.streamTask(
      { taskId, targetAgentId, input },
      {
        onStatus: applyStatusEvent,
        onError: (err) => {
          setActiveTasks(prev => prev.map(t => t.taskId === taskId
            ? { ...t, status: 'FAILED', errorDetail: err instanceof Error ? err.message : 'Stream error.', updatedAt: Date.now(), cancelling: false }
            : t,
          ));
        },
        onClose: () => markStreamClosed(taskId),
      },
    );
    streamControllers.current.set(taskId, controller);
  }, [applyStatusEvent, markStreamClosed]);

  const handleTaskCancel = useCallback(async (taskId: string) => {
    setActiveTasks(prev => prev.map(t => t.taskId === taskId ? { ...t, cancelling: true } : t));
    try {
      // Server-side cancel — flips the task to CANCELLED in the BE and the
      // existing SSE stream emits one final event we'll receive via
      // applyStatusEvent. We also abort the controller defensively in case
      // the BE drops the terminal event.
      await A2aApi.cancelTask(taskId);
    } catch (err) {
      // 404 means the task is already terminal or unknown. Surface as a
      // local FAILED only if the row is still non-terminal.
      setActiveTasks(prev => prev.map(t => {
        if (t.taskId !== taskId) return t;
        if ((t.status !== 'CONNECTING' && isTerminalA2aStatus(t.status))) return { ...t, cancelling: false };
        return {
          ...t,
          status: 'FAILED',
          errorDetail: err instanceof Error ? err.message : 'Cancel failed.',
          updatedAt: Date.now(),
          cancelling: false,
        };
      }));
    } finally {
      // Abort the SSE stream regardless; the row's terminal status was
      // either delivered by SSE or set manually above.
      streamControllers.current.get(taskId)?.abort();
      streamControllers.current.delete(taskId);
    }
  }, []);

  const handleClearTerminal = useCallback(() => {
    setActiveTasks(prev => prev.filter(t =>
      t.status !== 'COMPLETED' && t.status !== 'FAILED' && t.status !== 'CANCELLED',
    ));
  }, []);

  const handleDeregister = async (alias: string) => {
    if (!window.confirm(`Deregister peer "${alias}"? This removes the trust relationship.`)) return;
    deregisterPeer.mutate(alias);
  };

  const handleRefresh = () => {
    refetchPeers();
    refetchCards();
  };

  // ── Peer Table Columns ─────────────────────────────────────────

  const peerColumns = useMemo<ColumnDef<RemoteAgentRegistration, unknown>[]>(() => [
    {
      accessorKey: 'alias',
      header: 'Alias',
      cell: ({ getValue }) => (
        <div className="flex items-center gap-2">
          <LuGlobe className="w-4 h-4 text-agent-blue shrink-0" />
          <span className="font-medium text-theme-foreground">{getValue() as string}</span>
        </div>
      ),
    },
    {
      accessorKey: 'remoteAgentId',
      header: 'Remote Agent ID',
      cell: ({ getValue }) => (
        <span className="font-mono text-xs text-theme-muted truncate block max-w-50" title={getValue() as string}>
          {getValue() as string}
        </span>
      ),
    },
    {
      accessorKey: 'baseUrl',
      header: 'Endpoint',
      cell: ({ getValue }) => (
        <span className="font-mono text-xs text-theme-muted truncate block max-w-[280px]" title={getValue() as string}>
          {getValue() as string}
        </span>
      ),
    },
    {
      accessorKey: 'registeredAt',
      header: 'Registered',
      cell: ({ getValue }) => {
        const val = getValue() as string;
        return (
          <span className="text-xs text-theme-muted">
            {val ? new Date(val).toLocaleDateString() : '-'}
          </span>
        );
      },
    },
    {
      accessorKey: 'lastVerifiedAt',
      header: 'Last Verified',
      cell: ({ getValue }) => {
        const val = getValue() as string | null;
        return val ? (
          <Badge variant="ghost" className="text-xs font-mono text-active-green">{new Date(val).toLocaleDateString()}</Badge>
        ) : (
          <Badge variant="ghost" className="text-xs font-mono text-warn-amber">Unverified</Badge>
        );
      },
    },
    {
      id: 'actions',
      header: '',
      enableSorting: false,
      cell: ({ row }) => (
        <div className="flex justify-end">
          <Button
            variant="ghost"
            size="sm"
            className="text-error-red hover:bg-error-red/10"
            onClick={() => handleDeregister(row.original.alias)}
            title="Deregister peer"
          >
            <LuTrash2 className="w-3.5 h-3.5" />
          </Button>
        </div>
      ),
    },
  ], []);

  // ── Agent Card Table Columns ───────────────────────────────────

  const cardColumns = useMemo<ColumnDef<AgentCard, unknown>[]>(() => [
    {
      accessorKey: 'name',
      header: 'Agent',
      cell: ({ row }) => (
        <div className="min-w-0">
          <div className="font-medium text-theme-foreground truncate">{row.original.name}</div>
          <div className="text-xs text-theme-muted truncate">{row.original.description}</div>
        </div>
      ),
    },
    {
      accessorKey: 'modelId',
      header: 'Model',
      cell: ({ getValue }) => (
        <Badge variant="ghost" className="text-xs font-mono">{getValue() as string}</Badge>
      ),
    },
    {
      accessorKey: 'securityTier',
      header: 'Tier',
      cell: ({ getValue }) => {
        const tier = getValue() as number;
        const color = tier >= 3 ? 'text-active-green' : tier === 2 ? 'text-warn-amber' : 'text-theme-muted';
        return (
          <div className="flex items-center gap-1.5">
            <LuShield className={`w-3.5 h-3.5 ${color}`} />
            <span className={`text-xs font-mono ${color}`}>T{tier}</span>
          </div>
        );
      },
    },
    {
      accessorKey: 'capabilities',
      header: 'Capabilities',
      cell: ({ getValue }) => {
        const caps = getValue() as string[];
        return (
          <div className="flex flex-wrap gap-1 max-w-75">
            {caps?.slice(0, 3).map((c) => (
              <Badge key={c} variant="ghost" className="text-[10px] font-mono">{c}</Badge>
            ))}
            {(caps?.length ?? 0) > 3 && (
              <Badge variant="ghost" className="text-[10px] font-mono text-theme-muted">+{caps.length - 3}</Badge>
            )}
          </div>
        );
      },
    },
    {
      id: 'actions',
      header: '',
      enableSorting: false,
      cell: ({ row }) => (
        <Button
          variant="ghost"
          size="sm"
          onClick={() => setSelectedCardAgent({ id: row.original.agentId, name: row.original.name })}
          title="View A2A Card"
          className="text-theme-muted hover:text-agent-blue"
        >
          <LuEye className="w-3.5 h-3.5" />
        </Button>
      ),
    },
  ], []);

  const error = peersError || cardsError;

  return (
    <PageContainer variant="dashboard">
      <PageHeader
        icon={LuGlobe}
        title="Network Mesh"
        subtitle="Manage trusted A2A peer agents and view local capability cards."
        actions={
          <>
            <Button variant="ghost" size="sm" onClick={handleRefresh} className="gap-1.5">
              <LuRefreshCw className="w-4 h-4" /> Refresh
            </Button>
            <Button onClick={() => setIsRegisterOpen(true)} className="gap-2">
              <LuPlus className="w-4 h-4" /> Register Peer
            </Button>
          </>
        }
      />

      {error && (
        <Alert severity="error" title="Network Error">
          {(error as Error)?.message || 'Failed to load A2A network data.'}
        </Alert>
      )}

      {/* Remote Peers Section */}
      <section>
        <Typography.Heading level={3} className="mb-3 text-theme-foreground">
          Remote Peers
          <Badge variant="ghost" className="ml-2 text-xs font-mono">{peers.length}</Badge>
        </Typography.Heading>
        {peersLoading ? (
          <div className="space-y-2">
            {[1, 2, 3].map(i => <div key={i} className="h-12 bg-obsidian-elevated/50 rounded-md animate-pulse" />)}
          </div>
        ) : (
          <DataTable
            columns={peerColumns}
            data={peers}
            enablePagination
            defaultPageSize={25}
            emptyMessage="No remote peers registered. Click 'Register Peer' to connect an external agent."
          />
        )}
      </section>

      {/* A2A Task Dispatch + Active Tasks */}
      <section className="space-y-4">
        <Typography.Heading level={3} className="text-theme-foreground">
          Tasks
        </Typography.Heading>
        <A2aTaskSubmitPanel
          peers={peers}
          peersLoading={peersLoading}
          onSubmit={handleTaskSubmit}
          disabled={peers.length === 0}
        />
        <A2aActiveTasksPanel
          tasks={activeTasks}
          onCancel={handleTaskCancel}
          onClearTerminal={handleClearTerminal}
        />
      </section>

      {/* Local Agent Cards Section */}
      <section>
        <Typography.Heading level={3} className="mb-3 text-theme-foreground">
          Local Agent Cards
          <Badge variant="ghost" className="ml-2 text-xs font-mono">{cards.length}</Badge>
        </Typography.Heading>
        {cardsLoading ? (
          <div className="space-y-2">
            {[1, 2, 3].map(i => <div key={i} className="h-12 bg-obsidian-elevated/50 rounded-md animate-pulse" />)}
          </div>
        ) : (
          <DataTable
            columns={cardColumns}
            data={cards}
            enablePagination
            defaultPageSize={25}
            emptyMessage="No local agents have published capability cards."
          />
        )}
      </section>

      {/* Modals */}
      <PeerRegistrationModal isOpen={isRegisterOpen} onClose={() => setIsRegisterOpen(false)} />

      {selectedCardAgent && (
        <AgentCardViewer
          agentId={selectedCardAgent.id}
          agentName={selectedCardAgent.name}
          isOpen={!!selectedCardAgent}
          onClose={() => setSelectedCardAgent(null)}
        />
      )}
    </PageContainer>
  );
};
