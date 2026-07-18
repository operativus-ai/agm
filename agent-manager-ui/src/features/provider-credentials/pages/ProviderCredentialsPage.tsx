import React, { useEffect, useMemo, useState } from 'react';
import type { ColumnDef } from '@tanstack/react-table';
import { LuKey, LuPlus, LuPencil, LuTrash2 } from 'react-icons/lu';
import { PageContainer } from '../../../shared/components/ui/PageContainer';
import { PageHeader } from '../../../shared/components/ui/PageHeader';
import { Button } from '../../../shared/components/ui/Button';
import { Alert } from '../../../shared/components/ui/Alert';
import { Badge } from '../../../shared/components/ui/Badge';
import { DataTable } from '../../../shared/components/ui/DataTable';
import { Dialog } from '../../../shared/components/ui/Dialog';
import { ProviderCredentialsApi } from '../api/provider-credentials-api';
import type { ProviderCredentialRequest, ProviderCredentialResponse } from '../types/provider-credentials.types';
import { ProviderCredentialFormModal } from '../components/ProviderCredentialFormModal';

export const ProviderCredentialsPage: React.FC = () => {
  const [credentials, setCredentials] = useState<ProviderCredentialResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [isFormOpen, setIsFormOpen] = useState(false);
  const [editing, setEditing] = useState<ProviderCredentialResponse | null>(null);

  const [deleteTarget, setDeleteTarget] = useState<ProviderCredentialResponse | null>(null);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  useEffect(() => {
    void load();
  }, []);

  const load = async () => {
    try {
      setLoading(true);
      setError(null);
      const data = await ProviderCredentialsApi.list();
      setCredentials(data);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load provider credentials');
    } finally {
      setLoading(false);
    }
  };

  const handleCreate = () => {
    setEditing(null);
    setIsFormOpen(true);
  };

  const handleEdit = (row: ProviderCredentialResponse) => {
    setEditing(row);
    setIsFormOpen(true);
  };

  const handleSave = async (id: string | null, data: ProviderCredentialRequest) => {
    if (id) {
      const updated = await ProviderCredentialsApi.update(id, data);
      setCredentials(prev => prev.map(r => r.id === updated.id ? updated : r));
    } else {
      const created = await ProviderCredentialsApi.create(data);
      // Upsert by (orgId, provider) — refresh the list so dedup happens server-side.
      await load();
      // If still here (no error), keep created in state as a hint while load runs.
      setCredentials(prev => prev.some(r => r.id === created.id) ? prev : [...prev, created]);
    }
  };

  const handleDeleteConfirm = async () => {
    if (!deleteTarget) return;
    try {
      await ProviderCredentialsApi.delete(deleteTarget.id);
      setCredentials(prev => prev.filter(r => r.id !== deleteTarget.id));
      setDeleteTarget(null);
    } catch (err) {
      setDeleteError(err instanceof Error ? err.message : 'Failed to delete credential');
    }
  };

  const columns = useMemo<ColumnDef<ProviderCredentialResponse, unknown>[]>(() => [
    {
      accessorKey: 'provider',
      header: 'Provider',
      cell: ({ getValue }) => (
        <Badge variant="info" className="text-xs font-mono">{getValue() as string}</Badge>
      ),
    },
    {
      accessorKey: 'label',
      header: 'Label',
      cell: ({ getValue }) => {
        const label = getValue() as string | null;
        return label
          ? <span className="text-sm">{label}</span>
          : <span className="text-xs text-(--theme-muted) italic">—</span>;
      },
    },
    {
      accessorKey: 'apiKeyPreview',
      header: 'Key',
      cell: ({ getValue }) => (
        <code className="font-mono text-xs">{getValue() as string}</code>
      ),
    },
    {
      accessorKey: 'updatedAt',
      header: 'Last Updated',
      cell: ({ getValue }) => {
        const ts = getValue() as string | null;
        return ts
          ? <span className="text-xs text-(--theme-muted)">{new Date(ts).toLocaleString()}</span>
          : <span className="text-xs text-(--theme-muted) italic">never</span>;
      },
    },
    {
      accessorKey: 'updatedBy',
      header: 'Updated By',
      cell: ({ getValue }) => {
        const by = getValue() as string | null;
        return by
          ? <span className="text-xs">{by}</span>
          : <span className="text-xs text-(--theme-muted) italic">—</span>;
      },
    },
    {
      id: 'actions',
      header: '',
      enableSorting: false,
      cell: ({ row }) => (
        <div className="flex items-center justify-end gap-1">
          <button
            type="button"
            onClick={() => handleEdit(row.original)}
            aria-label="Edit credential"
            title="Edit"
            className="btn btn-ghost btn-sm btn-square rounded-md text-(--theme-muted) hover:text-(--theme-foreground)"
          >
            <LuPencil className="w-4 h-4" />
          </button>
          <button
            type="button"
            onClick={() => { setDeleteError(null); setDeleteTarget(row.original); }}
            aria-label="Delete credential"
            title="Delete"
            className="btn btn-ghost btn-sm btn-square rounded-md text-(--theme-muted) hover:text-error"
          >
            <LuTrash2 className="w-4 h-4" />
          </button>
        </div>
      ),
    },
  ], []);

  return (
    <PageContainer variant="dashboard">
      <PageHeader
        icon={LuKey}
        title="Provider Credentials"
        subtitle="LLM API keys per (org, provider). Keys are encrypted at rest. ModelEntity.apiKey overrides take precedence when set."
        actions={
          <Button size="sm" onClick={handleCreate} className="gap-1.5">
            <LuPlus className="w-4 h-4" /> Add Credential
          </Button>
        }
      />

      {error && <Alert severity="error" title="Error">{error}</Alert>}

      {loading ? (
        <div className="space-y-2">
          {[1, 2, 3].map(i => <div key={i} className="h-12 bg-obsidian-elevated/50 rounded-md animate-pulse" />)}
        </div>
      ) : (
        <DataTable
          columns={columns}
          data={credentials}
          enablePagination
          defaultPageSize={25}
          emptyMessage="No provider credentials configured. Click Add Credential to register one."
        />
      )}

      <ProviderCredentialFormModal
        isOpen={isFormOpen}
        onClose={() => setIsFormOpen(false)}
        onSave={handleSave}
        credential={editing}
      />

      <Dialog
        isOpen={!!deleteTarget}
        setIsOpen={(open) => { if (!open) setDeleteTarget(null); }}
        title="Delete Provider Credential"
        content={
          deleteTarget
            ? `Delete the ${deleteTarget.provider} credential${deleteTarget.label ? ` "${deleteTarget.label}"` : ''}? Models using this credential will fail until a replacement is configured or each model carries its own per-model api_key.`
            : ''
        }
        severity="error"
        confirmLabel="Delete"
        cancelLabel="Cancel"
        onConfirm={handleDeleteConfirm}
        onCancel={() => setDeleteTarget(null)}
      />

      {deleteError && <Alert severity="error" title="Delete Failed">{deleteError}</Alert>}
    </PageContainer>
  );
};
