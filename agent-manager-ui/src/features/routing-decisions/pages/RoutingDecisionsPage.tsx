import React, { useEffect, useState } from 'react';
import { LuList } from 'react-icons/lu';
import type { ColumnDef } from '@tanstack/react-table';
import { PageContainer } from '../../../shared/components/ui/PageContainer';
import { PageHeader } from '../../../shared/components/ui/PageHeader';
import { Alert } from '../../../shared/components/ui/Alert';
import { Badge } from '../../../shared/components/ui/Badge';
import { DataTable } from '../../../shared/components/ui/DataTable';
import { RoutingDecisionsApi } from '../api/routing-decisions-api';
import type { RoutingDecisionResponse, RoutingDecisionsPage as Page } from '../types/routing-decisions.types';

const PAGE_SIZE = 50;

const STRATEGY_BADGE: Record<string, 'success' | 'warning' | 'info' | 'error'> = {
  DEFAULT_ROUTER: 'success',
  LLM_CLASSIFIER: 'info',
  SEMANTIC_SCORING: 'info',
  RULE_SUBSTRING: 'warning',
  FALLBACK: 'warning',
  NONE: 'error',
};

const ROUTING_COLUMNS: ColumnDef<RoutingDecisionResponse, unknown>[] = [
  { accessorKey: 'createdAt', header: 'When', cell: ({ getValue }) => <span className="font-mono text-xs">{getValue() as string}</span> },
  {
    accessorKey: 'strategyUsed',
    header: 'Strategy',
    cell: ({ getValue }) => {
      const strategy = (getValue() as string) ?? 'NONE';
      return <Badge variant={STRATEGY_BADGE[strategy] ?? 'info'}>{strategy}</Badge>;
    },
  },
  { accessorKey: 'resolvedAgentId', header: 'Resolved Agent', cell: ({ getValue }) => <span className="font-mono text-xs">{(getValue() as string) ?? '—'}</span> },
  { accessorKey: 'confidence', header: 'Confidence', cell: ({ getValue }) => { const c = getValue() as number | null; return c != null ? c.toFixed(3) : '—'; } },
  { accessorKey: 'latencyMs', header: 'Latency (ms)', cell: ({ getValue }) => (getValue() as number | null) ?? '—' },
  { accessorKey: 'candidateCount', header: 'Candidates', cell: ({ getValue }) => (getValue() as number | null) ?? '—' },
  { accessorKey: 'rationale', header: 'Rationale', enableSorting: false, cell: ({ getValue }) => <span className="text-xs">{(getValue() as string) ?? '—'}</span> },
];

export const RoutingDecisionsPage: React.FC = () => {
  const [data, setData] = useState<Page | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [page, setPage] = useState(0);
  const [strategy, setStrategy] = useState<RoutingDecisionResponse['strategyUsed']>(null);

  useEffect(() => {
    void load();
  }, [page, strategy]);

  const load = async () => {
    try {
      setLoading(true);
      setError(null);
      const result = await RoutingDecisionsApi.list({ strategy: strategy ?? undefined, page, size: PAGE_SIZE });
      setData(result);
    } catch (err: any) {
      setError(err?.message || 'Failed to load routing decisions');
    } finally {
      setLoading(false);
    }
  };

  return (
    <PageContainer variant="dashboard">
      <PageHeader
        icon={LuList}
        title="Routing Decisions"
        subtitle="Telemetry of every universal-dispatch resolveAgentId call"
      />

      {error && <Alert severity="error" className="mb-4">{error}</Alert>}

      <div className="mb-4 flex items-center gap-2">
        <label className="text-sm text-theme-muted">Strategy:</label>
        <select
          className="select select-sm select-bordered"
          value={strategy ?? ''}
          onChange={e => {
            const v = e.target.value as RoutingDecisionResponse['strategyUsed'];
            setStrategy(v || null);
            setPage(0);
          }}
        >
          <option value="">All</option>
          <option value="DEFAULT_ROUTER">DEFAULT_ROUTER</option>
          <option value="LLM_CLASSIFIER">LLM_CLASSIFIER</option>
          <option value="SEMANTIC_SCORING">SEMANTIC_SCORING</option>
          <option value="RULE_SUBSTRING">RULE_SUBSTRING</option>
          <option value="FALLBACK">FALLBACK</option>
          <option value="NONE">NONE</option>
        </select>
      </div>

      {loading && !data ? (
        <p className="text-theme-muted">Loading…</p>
      ) : data ? (
        <DataTable
          columns={ROUTING_COLUMNS}
          data={data.content}
          manualPagination
          pageIndex={page}
          pageSize={PAGE_SIZE}
          totalElements={data.totalElements}
          onPageChange={setPage}
          emptyMessage="No decisions recorded for the current filter."
        />
      ) : null}
    </PageContainer>
  );
};
