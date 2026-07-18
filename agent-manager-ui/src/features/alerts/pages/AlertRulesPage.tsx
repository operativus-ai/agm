import React, { useMemo, useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import type { ColumnDef } from '@tanstack/react-table';
import { LuBell, LuPencil, LuTrash2, LuPlus } from 'react-icons/lu';

import { PageContainer } from '../../../shared/components/ui/PageContainer';
import { PageHeader } from '../../../shared/components/ui/PageHeader';
import { Button } from '../../../shared/components/ui/Button';
import { Badge } from '../../../shared/components/ui/Badge';
import { DataTable } from '../../../shared/components/ui/DataTable';
import { Dialog } from '../../../shared/components/ui/Dialog';

import { alertRulesApi, type AlertRule, type AlertSeverity } from '../api/alertRulesApi';
import { AlertRuleFormModal } from '../components/AlertRuleFormModal';

const severityVariant = (sev: AlertSeverity) => {
  switch (sev) {
    case 'CRITICAL':
      return 'error' as const;
    case 'WARNING':
      return 'warning' as const;
    case 'INFO':
    default:
      return 'secondary' as const;
  }
};

export const AlertRulesPage: React.FC = () => {
  const qc = useQueryClient();

  const { data: rules = [], isLoading, error } = useQuery({
    queryKey: ['alert-rules', 'list'],
    queryFn: () => alertRulesApi.list(),
    staleTime: 30_000,
  });

  const [isFormOpen, setIsFormOpen] = useState(false);
  const [editing, setEditing] = useState<AlertRule | null>(null);
  const [deleting, setDeleting] = useState<AlertRule | null>(null);

  const deleteMutation = useMutation({
    mutationFn: (id: string) => alertRulesApi.delete(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['alert-rules'] });
      setDeleting(null);
    },
  });

  const toggleMutation = useMutation({
    mutationFn: (rule: AlertRule) =>
      alertRulesApi.update(rule.id, {
        name: rule.name,
        description: rule.description ?? undefined,
        metricName: rule.metricName,
        condition: rule.condition,
        threshold: rule.threshold,
        windowSeconds: rule.windowSeconds,
        severity: rule.severity,
        enabled: !rule.enabled,
        notificationChannel: rule.notificationChannel ?? undefined,
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['alert-rules'] }),
  });

  const openCreate = () => {
    setEditing(null);
    setIsFormOpen(true);
  };

  const openEdit = (rule: AlertRule) => {
    setEditing(rule);
    setIsFormOpen(true);
  };

  const columns = useMemo<ColumnDef<AlertRule, unknown>[]>(
    () => [
      {
        id: 'name',
        header: 'Name',
        cell: ({ row }) => (
          <div>
            <div className="font-semibold">{row.original.name}</div>
            {row.original.description && (
              <div className="text-xs text-theme-muted line-clamp-1">
                {row.original.description}
              </div>
            )}
          </div>
        ),
      },
      {
        id: 'metric',
        header: 'Metric',
        cell: ({ row }) => (
          <code className="font-mono text-xs text-theme-muted">
            {row.original.metricName}
          </code>
        ),
      },
      {
        id: 'condition',
        header: 'Condition',
        cell: ({ row }) => (
          <span className="font-mono text-xs">
            {row.original.condition} {row.original.threshold}
            <span className="text-theme-muted ml-1">
              / {row.original.windowSeconds}s
            </span>
          </span>
        ),
      },
      {
        id: 'severity',
        header: 'Severity',
        cell: ({ row }) => (
          <Badge variant={severityVariant(row.original.severity)}>
            {row.original.severity}
          </Badge>
        ),
      },
      {
        id: 'enabled',
        header: 'Enabled',
        cell: ({ row }) => (
          <button
            type="button"
            className={`px-2 py-0.5 text-xs rounded border transition-colors ${
              row.original.enabled
                ? 'bg-success/10 text-success border-success/30 hover:bg-success/20'
                : 'bg-obsidian-elevated text-theme-muted border-obsidian-stroke hover:bg-obsidian-elevated/70'
            }`}
            onClick={() => toggleMutation.mutate(row.original)}
            disabled={toggleMutation.isPending}
            title={row.original.enabled ? 'Click to disable' : 'Click to enable'}
          >
            {row.original.enabled ? 'ON' : 'OFF'}
          </button>
        ),
      },
      {
        id: 'channel',
        header: 'Channel',
        cell: ({ row }) => (
          <span className="text-theme-muted text-xs font-mono">
            {row.original.notificationChannel ?? '—'}
          </span>
        ),
      },
      {
        id: 'actions',
        header: '',
        cell: ({ row }) => (
          <div className="flex items-center gap-1 justify-end">
            <button
              type="button"
              className="p-1.5 rounded hover:bg-obsidian-elevated text-theme-muted hover:text-theme-foreground"
              onClick={() => openEdit(row.original)}
              title="Edit"
            >
              <LuPencil size={14} />
            </button>
            <button
              type="button"
              className="p-1.5 rounded hover:bg-error/10 text-error hover:text-error"
              onClick={() => setDeleting(row.original)}
              title="Delete"
            >
              <LuTrash2 size={14} />
            </button>
          </div>
        ),
      },
    ],
    [toggleMutation]
  );

  return (
    <PageContainer variant="dashboard">
      <PageHeader
        icon={LuBell}
        title="Alert Rules"
        subtitle="Metric thresholds that fire alerts when breached. Wire a rule to a notification channel (from Alert Integrations) to route events."
        actions={
          <Button variant="primary" onClick={openCreate}>
            <LuPlus className="mr-1.5" size={14} />
            Create rule
          </Button>
        }
      />

      {error && (
        <div className="bg-error/10 border border-error/30 text-error text-sm rounded px-4 py-3 mb-4">
          Failed to load alert rules: {(error as Error).message}
        </div>
      )}

      <div className="bg-obsidian-card border border-obsidian-stroke/50 rounded-xl overflow-hidden">
        {isLoading ? (
          <div className="p-10 text-center text-theme-muted">Loading…</div>
        ) : rules.length === 0 ? (
          <div className="p-10 text-center text-theme-muted">
            No alert rules configured yet.
            <div className="mt-3">
              <Button variant="outline" size="sm" onClick={openCreate}>
                Create your first rule
              </Button>
            </div>
          </div>
        ) : (
          <DataTable columns={columns} data={rules} enablePagination defaultPageSize={25} />
        )}
      </div>

      <AlertRuleFormModal
        isOpen={isFormOpen}
        existing={editing}
        onClose={() => setIsFormOpen(false)}
        onSaved={() => qc.invalidateQueries({ queryKey: ['alert-rules'] })}
      />

      <Dialog
        isOpen={!!deleting}
        setIsOpen={(open) => !open && setDeleting(null)}
        title={deleting ? `Delete rule "${deleting.name}"?` : ''}
        severity="error"
        canBeCanceled
        shouldCloseOnConfirm={false}
        onConfirm={() => deleting && deleteMutation.mutate(deleting.id)}
        onCancel={() => setDeleting(null)}
      >
        <div className="text-sm text-theme-muted">
          This stops the rule from evaluating. Historical alert events from
          this rule are preserved. This action cannot be undone.
        </div>
        {deleteMutation.error && (
          <div className="mt-3 text-sm text-error">
            {(deleteMutation.error as Error).message}
          </div>
        )}
      </Dialog>
    </PageContainer>
  );
};
