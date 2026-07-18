import React, { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import type { ColumnDef } from '@tanstack/react-table';
import { LuEye, LuPlay, LuRefreshCw } from 'react-icons/lu';

import { PageContainer } from '../../../shared/components/ui/PageContainer';
import { PageHeader } from '../../../shared/components/ui/PageHeader';
import { Alert } from '../../../shared/components/ui/Alert';
import { Badge } from '../../../shared/components/ui/Badge';
import { Button } from '../../../shared/components/ui/Button';
import { DataTable } from '../../../shared/components/ui/DataTable';
import { Input } from '../../../shared/components/ui/Input';
import { Select } from '../../../shared/components/ui/Select';

import { runsApi } from '../api/runsApi';
import type { AgentRunResponse, RunStatus } from '../types/runs';
import { RUN_STATUSES } from '../types/runs';

const PAGE_SIZE = 20;

const statusVariant = (s: RunStatus): 'success' | 'error' | 'warning' | 'info' | 'ghost' => {
  if (s === 'COMPLETED' || s === 'APPROVED') return 'success';
  if (s === 'FAILED' || s === 'REJECTED' || s === 'EXPIRED' || s === 'CANCELLED') return 'error';
  if (s === 'RUNNING' || s === 'PROCESSING') return 'info';
  if (s === 'PAUSED' || s === 'QUEUED' || s === 'PENDING') return 'warning';
  return 'ghost';
};

const formatCost = (v: AgentRunResponse['totalCostUsd']): string => {
  if (v === null || v === undefined) return '—';
  const n = typeof v === 'string' ? Number(v) : v;
  return Number.isFinite(n) ? `$${n.toFixed(4)}` : '—';
};

const formatTokens = (v: number | null): string => (v == null ? '—' : v.toLocaleString());

const formatDuration = (ms: number | null): string => {
  if (ms == null) return '—';
  if (ms < 1000) return `${ms}ms`;
  const s = ms / 1000;
  return s < 60 ? `${s.toFixed(1)}s` : `${(s / 60).toFixed(1)}m`;
};

export const RunsPage: React.FC = () => {
  const navigate = useNavigate();
  const [agentId, setAgentId] = useState('');
  const [sessionId, setSessionId] = useState('');
  const [status, setStatus] = useState<RunStatus | ''>('');
  const [pageIndex, setPageIndex] = useState(0);

  const queryParams = {
    agentId: agentId.trim() || undefined,
    sessionId: sessionId.trim() || undefined,
    status: status || undefined,
    page: pageIndex,
    size: PAGE_SIZE,
  };

  const { data, isLoading, isFetching, error, refetch } = useQuery({
    queryKey: ['runs', 'list', queryParams],
    queryFn: () => runsApi.list(queryParams),
    staleTime: 30_000,
  });

  const rows = data?.content ?? [];
  const totalElements = data?.page.totalElements ?? 0;

  const resetPage = (mutator: () => void) => {
    mutator();
    setPageIndex(0);
  };

  const columns = useMemo<ColumnDef<AgentRunResponse, unknown>[]>(() => [
    {
      accessorKey: 'status',
      header: 'Status',
      cell: ({ getValue }) => {
        const s = getValue() as RunStatus;
        return <Badge variant={statusVariant(s)} className="text-xs">{s}</Badge>;
      },
    },
    {
      accessorKey: 'agentId',
      header: 'Agent',
      cell: ({ getValue }) => (
        <span className="font-mono text-xs truncate block max-w-[180px]" title={getValue() as string}>
          {(getValue() as string) || '—'}
        </span>
      ),
    },
    {
      accessorKey: 'model',
      header: 'Model',
      cell: ({ getValue }) => (
        <span className="text-xs text-(--theme-muted)">{(getValue() as string | null) || '—'}</span>
      ),
    },
    {
      accessorKey: 'durationMs',
      header: 'Duration',
      cell: ({ getValue }) => (
        <span className="text-xs whitespace-nowrap">{formatDuration(getValue() as number | null)}</span>
      ),
    },
    {
      accessorKey: 'inputTokens',
      header: 'In',
      cell: ({ getValue }) => (
        <span className="text-xs text-(--theme-muted) whitespace-nowrap">{formatTokens(getValue() as number | null)}</span>
      ),
    },
    {
      accessorKey: 'outputTokens',
      header: 'Out',
      cell: ({ getValue }) => (
        <span className="text-xs text-(--theme-muted) whitespace-nowrap">{formatTokens(getValue() as number | null)}</span>
      ),
    },
    {
      accessorKey: 'totalCostUsd',
      header: 'Cost',
      cell: ({ getValue }) => (
        <span className="text-xs whitespace-nowrap">{formatCost(getValue() as AgentRunResponse['totalCostUsd'])}</span>
      ),
    },
    {
      accessorKey: 'errorType',
      header: 'Error',
      cell: ({ getValue }) => {
        const v = (getValue() as string | null) || '';
        return v ? <Badge variant="error" className="text-xs">{v}</Badge> : <span className="text-xs text-(--theme-muted)">—</span>;
      },
    },
    {
      accessorKey: 'createdAt',
      header: 'Started',
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
      cell: ({ row }) => (
        <div className="flex items-center justify-end">
          <Button
            size="sm"
            variant="ghost"
            title="View run"
            className="px-2 text-(--theme-muted) hover:text-primary"
            onClick={() => navigate(`/runs/${row.original.id}`)}
          >
            <LuEye className="w-3.5 h-3.5" />
          </Button>
        </div>
      ),
    },
  ], [navigate]);

  return (
    <PageContainer variant="dashboard">
      <PageHeader
        icon={LuPlay}
        title="Run History"
        subtitle="Paginated list of agent runs across all agents and sessions."
        actions={
          <Button variant="outline" size="sm" onClick={() => refetch()} disabled={isFetching} className="gap-2">
            {isFetching ? <span className="loading loading-spinner loading-sm" /> : <LuRefreshCw className="w-4 h-4" />}
            Refresh
          </Button>
        }
      />

      <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl p-4">
        <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
          <Input
            label="Agent ID"
            value={agentId}
            onChange={(e) => resetPage(() => setAgentId(e.target.value))}
            placeholder="agent-..."
          />
          <Input
            label="Session ID"
            value={sessionId}
            onChange={(e) => resetPage(() => setSessionId(e.target.value))}
            placeholder="session-..."
          />
          <Select
            label="Status"
            value={status}
            options={[
              { value: '', label: 'All statuses' },
              ...RUN_STATUSES.map(s => ({ value: s, label: s })),
            ]}
            onChange={(e) => resetPage(() => setStatus((e.target.value as RunStatus) || ''))}
          />
        </div>
        <p className="mt-2 text-xs text-(--theme-muted)">
          Backend applies one filter at a time (precedence: session ID → agent ID → status).
        </p>
      </div>

      {error && (
        <Alert severity="error" title="Failed to load runs">
          {(error as Error).message}
        </Alert>
      )}

      {isLoading && rows.length === 0 ? (
        <div className="space-y-2">
          {[1, 2, 3, 4, 5].map(i => (
            <div key={i} className="h-12 bg-obsidian-elevated/50 rounded-md animate-pulse" />
          ))}
        </div>
      ) : (
        <DataTable
          columns={columns}
          data={rows}
          manualPagination
          pageIndex={pageIndex}
          pageSize={PAGE_SIZE}
          totalElements={totalElements}
          onPageChange={setPageIndex}
          emptyMessage="No runs match the current filters."
        />
      )}
    </PageContainer>
  );
};
