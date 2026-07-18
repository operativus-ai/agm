import React, { useMemo, useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import type { ColumnDef } from '@tanstack/react-table';
import { LuKeyRound, LuArrowLeft, LuTrash2, LuPlus, LuPower } from 'react-icons/lu';

import { PageContainer } from '../../../shared/components/ui/PageContainer';
import { PageHeader } from '../../../shared/components/ui/PageHeader';
import { Button } from '../../../shared/components/ui/Button';
import { Badge } from '../../../shared/components/ui/Badge';
import { DataTable } from '../../../shared/components/ui/DataTable';
import { Dialog } from '../../../shared/components/ui/Dialog';

import {
  agentCredentialsApi,
  type AgentCredential,
  type CredentialType,
} from '../api/agentCredentialsApi';
import { AgentCredentialFormModal } from '../components/AgentCredentialFormModal';

const typeBadgeVariant = (t: CredentialType) => {
  switch (t) {
    case 'OAUTH2':
      return 'primary' as const;
    case 'API_KEY':
      return 'secondary' as const;
    case 'BEARER':
      return 'neutral' as const;
    case 'JWT':
    default:
      return 'neutral' as const;
  }
};

export const AgentCredentialsPage: React.FC = () => {
  const { agentId } = useParams<{ agentId: string }>();
  const qc = useQueryClient();

  const { data: credentials = [], isLoading, error } = useQuery({
    queryKey: ['agent-credentials', agentId, 'list'],
    queryFn: () => agentCredentialsApi.list(agentId!),
    enabled: !!agentId,
    staleTime: 30_000,
  });

  const [isFormOpen, setIsFormOpen] = useState(false);
  const [deleting, setDeleting] = useState<AgentCredential | null>(null);

  const deleteMutation = useMutation({
    mutationFn: (credentialId: string) => agentCredentialsApi.delete(agentId!, credentialId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['agent-credentials', agentId] });
      setDeleting(null);
    },
  });

  const toggleMutation = useMutation({
    mutationFn: (c: AgentCredential) =>
      agentCredentialsApi.updateMetadata(agentId!, c.id, { enabled: !c.enabled }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['agent-credentials', agentId] }),
  });

  const columns = useMemo<ColumnDef<AgentCredential, unknown>[]>(
    () => [
      {
        id: 'provider',
        header: 'Provider',
        cell: ({ row }) => (
          <div>
            <div className="font-semibold">{row.original.providerName}</div>
            <div className="text-xs text-theme-muted font-mono">{row.original.id.slice(0, 12)}…</div>
          </div>
        ),
      },
      {
        id: 'type',
        header: 'Type',
        cell: ({ row }) => (
          <Badge variant={typeBadgeVariant(row.original.credentialType)}>
            {row.original.credentialType}
          </Badge>
        ),
      },
      {
        id: 'scopes',
        header: 'Scopes / Client',
        cell: ({ row }) => {
          const c = row.original;
          if (c.credentialType === 'OAUTH2') {
            return (
              <div className="text-xs">
                <div className="font-mono">{c.clientId ?? '—'}</div>
                {c.scopes && (
                  <div className="text-theme-muted truncate max-w-xs" title={c.scopes}>
                    {c.scopes}
                  </div>
                )}
              </div>
            );
          }
          return <span className="text-theme-muted text-xs">—</span>;
        },
      },
      {
        id: 'expires',
        header: 'Expires',
        cell: ({ row }) => (
          <span className="text-theme-muted text-xs font-mono">
            {row.original.expiresAt
              ? new Date(row.original.expiresAt).toLocaleDateString()
              : 'Never'}
          </span>
        ),
      },
      {
        id: 'enabled',
        header: 'Enabled',
        cell: ({ row }) => (
          <button
            type="button"
            className={`px-2 py-0.5 text-xs rounded border transition-colors inline-flex items-center gap-1 ${
              row.original.enabled
                ? 'bg-success/10 text-success border-success/30 hover:bg-success/20'
                : 'bg-obsidian-elevated text-theme-muted border-obsidian-stroke hover:bg-obsidian-elevated/70'
            }`}
            onClick={() => toggleMutation.mutate(row.original)}
            disabled={toggleMutation.isPending}
            title={row.original.enabled ? 'Click to disable' : 'Click to enable'}
          >
            <LuPower size={10} />
            {row.original.enabled ? 'ON' : 'OFF'}
          </button>
        ),
      },
      {
        id: 'actions',
        header: '',
        cell: ({ row }) => (
          <div className="flex items-center gap-1 justify-end">
            <button
              type="button"
              className="p-1.5 rounded hover:bg-error/10 text-error"
              onClick={() => setDeleting(row.original)}
              title="Delete credential"
            >
              <LuTrash2 size={14} />
            </button>
          </div>
        ),
      },
    ],
    [toggleMutation]
  );

  if (!agentId) {
    return (
      <PageContainer>
        <div className="p-6 text-theme-muted">Agent id missing from route.</div>
      </PageContainer>
    );
  }

  return (
    <PageContainer>
      <div className="mb-2">
        <Link to="/agents" className="text-xs text-theme-muted hover:text-theme-foreground inline-flex items-center gap-1">
          <LuArrowLeft size={12} /> Back to agents
        </Link>
      </div>

      <PageHeader
        icon={LuKeyRound}
        title="Credentials"
        subtitle={`Scoped auth contexts this agent uses for outbound calls. Secrets are write-once — rotate by deleting and recreating.`}
        actions={
          <Button variant="primary" onClick={() => setIsFormOpen(true)}>
            <LuPlus className="mr-1.5" size={14} />
            Add credential
          </Button>
        }
      />

      {error && (
        <div className="bg-error/10 border border-error/30 text-error text-sm rounded px-4 py-3 mb-4">
          Failed to load credentials: {(error as Error).message}
        </div>
      )}

      <div className="bg-obsidian-card border border-obsidian-stroke/50 rounded-xl overflow-hidden">
        {isLoading ? (
          <div className="p-10 text-center text-theme-muted">Loading…</div>
        ) : credentials.length === 0 ? (
          <div className="p-10 text-center text-theme-muted">
            No credentials configured for this agent.
            <div className="mt-3">
              <Button variant="outline" size="sm" onClick={() => setIsFormOpen(true)}>
                Add your first credential
              </Button>
            </div>
          </div>
        ) : (
          <DataTable columns={columns} data={credentials} enablePagination defaultPageSize={25} />
        )}
      </div>

      <AgentCredentialFormModal
        isOpen={isFormOpen}
        agentId={agentId}
        onClose={() => setIsFormOpen(false)}
        onSaved={() =>
          qc.invalidateQueries({ queryKey: ['agent-credentials', agentId] })
        }
      />

      <Dialog
        isOpen={!!deleting}
        setIsOpen={(open) => !open && setDeleting(null)}
        title={
          deleting
            ? `Delete ${deleting.credentialType} credential for "${deleting.providerName}"?`
            : ''
        }
        severity="error"
        canBeCanceled
        shouldCloseOnConfirm={false}
        onConfirm={() => deleting && deleteMutation.mutate(deleting.id)}
        onCancel={() => setDeleting(null)}
      >
        <div className="text-sm text-theme-muted">
          This permanently removes the credential. The secret is wiped from
          the server and cannot be recovered. If the agent is in-flight with
          this credential, its next outbound call will fail.
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
