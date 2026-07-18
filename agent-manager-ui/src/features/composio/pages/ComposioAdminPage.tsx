import React, { useState, useEffect, useCallback } from 'react';
import { LuZap } from 'react-icons/lu';
import { PageContainer } from '../../../shared/components/ui/PageContainer';
import { PageHeader } from '../../../shared/components/ui/PageHeader';
import {
  TabsRoot,
  TabsList,
  TabsTrigger,
  TabsContent,
} from '../../../shared/components/ui/Tabs';
import { composioAdminApi } from '../api/composioAdminApi';
import type {
  ComposioActionConfigResponse,
  ComposioConnectionConfigResponse,
  ConfigDriftResponse,
} from '../types';
import { ActionListTable } from '../components/ActionListTable';
import { ActionEditModal } from '../components/ActionEditModal';
import { ConnectionListTable } from '../components/ConnectionListTable';
import { ConnectionEditModal } from '../components/ConnectionEditModal';
import { ConfigDriftPanel } from '../components/ConfigDriftPanel';
import { CatalogBrowsePanel } from '../components/CatalogBrowsePanel';

export const ComposioAdminPage: React.FC = () => {
  // ── Actions state ──────────────────────────────────────────
  const [actions, setActions] = useState<ComposioActionConfigResponse[]>([]);
  const [actionsLoading, setActionsLoading] = useState(true);
  const [actionsError, setActionsError] = useState<string | null>(null);
  const [editingAction, setEditingAction] = useState<ComposioActionConfigResponse | null>(null);
  const [actionModalOpen, setActionModalOpen] = useState(false);

  // ── Drift state ────────────────────────────────────────────
  const [drift, setDrift] = useState<ConfigDriftResponse | null>(null);
  const [driftLoading, setDriftLoading] = useState(false);

  // ── Connection state ───────────────────────────────────────
  const [connection, setConnection] = useState<ComposioConnectionConfigResponse | null>(null);
  const [connectionLoading, setConnectionLoading] = useState(true);
  const [connectionError, setConnectionError] = useState<string | null>(null);
  const [editingConnection, setEditingConnection] = useState<ComposioConnectionConfigResponse | null>(null);
  const [connectionModalOpen, setConnectionModalOpen] = useState(false);

  // ── Fetch ─────────────────────────────────────────────────
  const loadActions = useCallback(async () => {
    setActionsLoading(true);
    setActionsError(null);
    try {
      const data = await composioAdminApi.listActions();
      setActions(data);
    } catch (err: any) {
      setActionsError(err?.message ?? 'Failed to load actions.');
    } finally {
      setActionsLoading(false);
    }
  }, []);

  const loadDrift = useCallback(async () => {
    setDriftLoading(true);
    try {
      const data = await composioAdminApi.getConfigDrift();
      setDrift(data);
    } catch {
      setDrift(null);
    } finally {
      setDriftLoading(false);
    }
  }, []);

  const loadConnection = useCallback(async () => {
    setConnectionLoading(true);
    setConnectionError(null);
    try {
      const data = await composioAdminApi.getConnection();
      setConnection(data);
    } catch (err: any) {
      // 404 = no connection configured yet
      if (err?.status === 404 || err?.message?.includes('404')) {
        setConnection(null);
      } else {
        setConnectionError(err?.message ?? 'Failed to load connection.');
      }
    } finally {
      setConnectionLoading(false);
    }
  }, []);

  useEffect(() => {
    loadActions();
    loadConnection();
  }, [loadActions, loadConnection]);

  // ── Action handlers ────────────────────────────────────────
  const handleAddAction = () => {
    setEditingAction(null);
    setActionModalOpen(true);
  };

  const handleEditAction = (action: ComposioActionConfigResponse) => {
    setEditingAction(action);
    setActionModalOpen(true);
  };

  const handleDeleteAction = async (action: ComposioActionConfigResponse) => {
    if (!window.confirm(`Delete action config "${action.actionName}"? This cannot be undone.`)) return;
    try {
      await composioAdminApi.deleteAction(action.id);
      await loadActions();
    } catch (err: any) {
      alert(err?.message ?? 'Failed to delete action.');
    }
  };

  // ── Connection handlers ────────────────────────────────────
  const handleEditConnection = (conn: ComposioConnectionConfigResponse | null) => {
    setEditingConnection(conn);
    setConnectionModalOpen(true);
  };

  const handleDeleteConnection = async (_conn: ComposioConnectionConfigResponse) => {
    if (!window.confirm('Remove the Composio connection for this org? This cannot be undone.')) return;
    try {
      await composioAdminApi.deleteConnection();
      setConnection(null);
    } catch (err: any) {
      alert(err?.message ?? 'Failed to delete connection.');
    }
  };

  return (
    <PageContainer variant="dashboard">
      <PageHeader
        icon={LuZap}
        title="Composio Admin"
        subtitle="Manage Composio action tier configurations and per-org connection settings."
      />

      <TabsRoot
        defaultValue="actions"
        onValueChange={(v) => { if (v === 'drift') loadDrift(); }}
      >
        <TabsList>
          <TabsTrigger value="actions">Action Configs</TabsTrigger>
          <TabsTrigger value="catalog">Catalog</TabsTrigger>
          <TabsTrigger value="connection">Connection</TabsTrigger>
          <TabsTrigger value="drift">Config Audit</TabsTrigger>
        </TabsList>

        <TabsContent value="actions">
          <div className="bg-(--theme-card) rounded-xl border border-(--theme-muted)/10 p-5 shadow-sm space-y-4 mt-4">
            <div>
              <h3 className="text-sm font-bold uppercase tracking-wider text-(--theme-muted)">
                Action Tier Configurations
              </h3>
              <p className="text-xs text-(--theme-muted) mt-1">
                Controls how each Composio action executes: auto (T1), HITL-gated (T2), or approval-required (T3).
              </p>
            </div>

            {actionsError && (
              <p className="text-sm text-error-red">{actionsError}</p>
            )}

            <ActionListTable
              actions={actions}
              loading={actionsLoading}
              onAdd={handleAddAction}
              onEdit={handleEditAction}
              onDelete={handleDeleteAction}
            />
          </div>
        </TabsContent>

        <TabsContent value="catalog">
          <div className="bg-(--theme-card) rounded-xl border border-(--theme-muted)/10 p-5 shadow-sm space-y-4 mt-4">
            <div>
              <h3 className="text-sm font-bold uppercase tracking-wider text-(--theme-muted)">
                Upstream Catalog
              </h3>
              <p className="text-xs text-(--theme-muted) mt-1">
                Browse the live Composio catalog and bulk-import an app's actions into the allowlist.
                Reads upstream Composio (not the AGM DB) — failures here are network/API-key related.
              </p>
            </div>
            <CatalogBrowsePanel />
          </div>
        </TabsContent>

        <TabsContent value="connection">
          <div className="bg-(--theme-card) rounded-xl border border-(--theme-muted)/10 p-5 shadow-sm space-y-4 mt-4">
            <div>
              <h3 className="text-sm font-bold uppercase tracking-wider text-(--theme-muted)">
                Org Connection
              </h3>
              <p className="text-xs text-(--theme-muted) mt-1">
                The Composio connection ID used by agents in this org to call external tools.
              </p>
            </div>

            {connectionError && (
              <p className="text-sm text-error-red">{connectionError}</p>
            )}

            <ConnectionListTable
              connection={connection}
              loading={connectionLoading}
              onEdit={handleEditConnection}
              onDelete={handleDeleteConnection}
            />
          </div>
        </TabsContent>
        <TabsContent value="drift">
          <div className="bg-(--theme-card) rounded-xl border border-(--theme-muted)/10 p-5 shadow-sm space-y-4 mt-4">
            <div>
              <h3 className="text-sm font-bold uppercase tracking-wider text-(--theme-muted)">
                Config Audit
              </h3>
              <p className="text-xs text-(--theme-muted) mt-1">
                Point-in-time snapshot comparing the live action registry against DB configs and per-org connection coverage.
              </p>
            </div>
            <ConfigDriftPanel drift={drift} loading={driftLoading} onRefresh={loadDrift} />
          </div>
        </TabsContent>
      </TabsRoot>

      <ActionEditModal
        isOpen={actionModalOpen}
        onClose={() => setActionModalOpen(false)}
        onSaved={loadActions}
        editing={editingAction}
      />

      <ConnectionEditModal
        isOpen={connectionModalOpen}
        onClose={() => setConnectionModalOpen(false)}
        onSaved={loadConnection}
        existing={editingConnection}
      />
    </PageContainer>
  );
};
