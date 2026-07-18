import React, { useEffect, useMemo, useState } from 'react';
import type { ModelConfig, ModelPingResult, ModelRequest, DefaultModelSlot } from '../types/models.types';
import type { ColumnDef } from '@tanstack/react-table';
import { ModelsApi } from '../api/models-api';
import { settingsApi } from '../../settings/api/settings-api';
import { PageHeader } from '../../../shared/components/ui/PageHeader';
import { Button } from '../../../shared/components/ui/Button';
import { Alert } from '../../../shared/components/ui/Alert';
import { Badge } from '../../../shared/components/ui/Badge';
import { DataTable } from '../../../shared/components/ui/DataTable';
import { ModelFormModal } from '../components/ModelFormModal';
import { ProviderIcon } from '../components/ProviderIcon';
import { Dialog } from '../../../shared/components/ui/Dialog';
import {
    LuBox, LuPlus, LuPencil, LuTrash2, LuCheck, LuX, LuRadio, LuCopy, LuDownload, LuUpload,
} from 'react-icons/lu';
import { PageContainer } from '../../../shared/components/ui/PageContainer';

/** §6 M-13 export schema. Bump on any breaking change to the projected fields below;
 *  importers reject anything other than the current schemaVersion. */
const EXPORT_SCHEMA_VERSION = '1';

interface ModelExportEnvelope {
  schemaVersion: string;
  exportedAt: string;
  model: ModelRequest;
}

/** Project a ModelConfig (server response, includes id + timestamps + counts + liveness)
 *  down to a ModelRequest-shaped payload (the create/update wire shape). The {@code apiKey}
 *  is intentionally NOT projected — exports never carry secrets, importers must supply
 *  the key out-of-band. */
const projectForExport = (m: ModelConfig): ModelRequest => ({
  name: m.name,
  provider: m.provider,
  baseUrl: m.baseUrl,
  modelName: m.modelName,
  supportsTools: m.supportsTools,
  supportsVision: m.supportsVision,
  supportsSystemInstructions: m.supportsSystemInstructions,
  maxContextTokens: m.maxContextTokens,
  maxOutputTokens: m.maxOutputTokens,
  thinkingBudgetTokens: m.thinkingBudgetTokens,
  modelType: m.modelType,
  // defaultSlot intentionally not carried — slot assignment is per-deployment.
  // §6 M-12: rate-limit override travels with the export so re-importing in another
  // environment preserves the operator-configured ceiling.
  rateLimitRpm: m.rateLimitRpm ?? undefined,
});

const sanitizeFilename = (name: string): string =>
  name.replace(/[^A-Za-z0-9._-]/g, '_').replace(/_+/g, '_').toLowerCase();

const SLOT_SETTINGS_KEYS: Record<DefaultModelSlot, string> = {
  ROUTER: 'DEFAULT_MODEL_ROUTER',
  FAST: 'DEFAULT_MODEL_FAST',
  HEAVY: 'DEFAULT_MODEL_HEAVY',
  EMBEDDING: 'DEFAULT_MODEL_EMBEDDING',
};

const SLOT_LABELS: Record<DefaultModelSlot, string> = {
  ROUTER: 'Router',
  FAST: 'Fast',
  HEAVY: 'Heavy',
  EMBEDDING: 'Embedding',
};

export const ModelsPage: React.FC = () => {
  const [models, setModels] = useState<ModelConfig[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [currentSettings, setCurrentSettings] = useState<Record<string, string>>({});

  const [isFormModalOpen, setIsFormModalOpen] = useState(false);
  const [selectedModelForEdit, setSelectedModelForEdit] = useState<ModelConfig | null>(null);

  const [deleteTarget, setDeleteTarget] = useState<ModelConfig | null>(null);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  /** id of the model whose test ping is in flight; locks the action until response. */
  const [pingingId, setPingingId] = useState<string | null>(null);
  /** Most recent ping outcome — surfaced as an inline Alert above the table. */
  const [pingResult, setPingResult] = useState<{ name: string; result: ModelPingResult } | null>(null);

  /** §6 M-13: ref for the hidden file input that backs the Import button. */
  const fileInputRef = React.useRef<HTMLInputElement | null>(null);

  const PAGE_SIZE = 20;
  const [pageIndex, setPageIndex] = useState(0);
  const [totalElements, setTotalElements] = useState(0);

  useEffect(() => {
    loadModels();
    settingsApi.getAllSettings().then(s => setCurrentSettings(s)).catch(() => {});
  }, [pageIndex]);

  const loadModels = async () => {
    try {
      setLoading(true);
      setError(null);
      const data = await ModelsApi.getModels({ page: pageIndex, size: PAGE_SIZE });
      if (data && data.content) {
        setModels(data.content);
        setTotalElements(data.page.totalElements);
      } else {
        setModels([]);
        setTotalElements(0);
      }
    } catch (err: any) {
      if (err.response?.status !== 404) {
        setError(err.message || 'Failed to load custom models');
      }
    } finally {
      setLoading(false);
    }
  };

  const handleCreateClick = () => {
    setSelectedModelForEdit(null);
    setIsFormModalOpen(true);
  };

  const handleEditClick = (model: ModelConfig) => {
    setSelectedModelForEdit(model);
    setIsFormModalOpen(true);
  };

  const handleDeleteConfirm = async () => {
    if (!deleteTarget) return;
    try {
      await ModelsApi.deleteModel(deleteTarget.id);
      setModels(prev => prev.filter(m => m.id !== deleteTarget.id));
      setDeleteTarget(null);
    } catch (err: any) {
      setDeleteError(err.message || 'Failed to delete model');
    }
  };

  const handleCloneClick = async (model: ModelConfig) => {
    const suggested = `${model.name} (Clone)`;
    const newName = window.prompt('Name for the cloned model:', suggested);
    if (newName === null) return;
    try {
      const cloned = await ModelsApi.cloneModel(model.id, newName);
      setModels(prev => [...prev, cloned]);
    } catch (err: any) {
      setError(err?.message || 'Failed to clone model');
    }
  };

  const handleTestClick = async (model: ModelConfig) => {
    setPingingId(model.id);
    setPingResult(null);
    try {
      const result = await ModelsApi.pingModel(model.id);
      setModels(prev => prev.map(m =>
        m.id === model.id
          ? { ...m, available: result.available, lastPingedAt: new Date().toISOString() }
          : m,
      ));
      setPingResult({ name: model.name, result });
    } catch (err: any) {
      setPingResult({
        name: model.name,
        result: {
          modelId: model.id,
          available: false,
          latencyMs: 0,
          errorMessage: err?.message || 'Request failed before reaching the backend',
        },
      });
    } finally {
      setPingingId(null);
    }
  };

  const handleExportClick = (model: ModelConfig) => {
    const envelope: ModelExportEnvelope = {
      schemaVersion: EXPORT_SCHEMA_VERSION,
      exportedAt: new Date().toISOString(),
      model: projectForExport(model),
    };
    const blob = new Blob([JSON.stringify(envelope, null, 2)], { type: 'application/json' });
    const href = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = href;
    link.download = `model-${sanitizeFilename(model.name)}.json`;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(href);
  };

  const handleImportClick = () => fileInputRef.current?.click();

  const handleImportFile = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file) return;

    let envelope: ModelExportEnvelope;
    try {
      const text = await file.text();
      envelope = JSON.parse(text) as ModelExportEnvelope;
    } catch (err: any) {
      setError(`Import failed: invalid JSON in ${file.name}`);
      return;
    }
    if (!envelope || envelope.schemaVersion !== EXPORT_SCHEMA_VERSION || !envelope.model) {
      setError(`Import failed: unsupported schemaVersion (expected "${EXPORT_SCHEMA_VERSION}")`);
      return;
    }
    try {
      const created = await ModelsApi.createModel(envelope.model);
      setModels(prev => [...prev, created]);
      setError(null);
    } catch (err: any) {
      setError(err?.message || 'Import failed: backend rejected the model');
    }
  };

  const handleSaveModel = async (id: string | null, modelData: ModelRequest) => {
    let savedModel: ModelConfig;
    if (id) {
      savedModel = await ModelsApi.updateModel(id, modelData);
      setModels(prev => prev.map(m => m.id === savedModel.id ? savedModel : m));
    } else {
      savedModel = await ModelsApi.createModel(modelData);
      setModels(prev => [...prev, savedModel]);
    }
    if (modelData.defaultSlot) {
      const key = SLOT_SETTINGS_KEYS[modelData.defaultSlot];
      setCurrentSettings(prev => ({ ...prev, [key]: savedModel.id }));
    }
  };

  const getActiveSlots = (modelId: string): DefaultModelSlot[] =>
    (Object.entries(SLOT_SETTINGS_KEYS) as [DefaultModelSlot, string][])
      .filter(([, key]) => currentSettings[key] === modelId)
      .map(([slot]) => slot);

  const CapabilityIcon: React.FC<{ supported?: boolean }> = ({ supported }) =>
    supported
      ? <LuCheck className="w-3.5 h-3.5 text-active-green" />
      : <LuX className="w-3.5 h-3.5 text-(--theme-muted) opacity-30" />;

  const columns = useMemo<ColumnDef<ModelConfig, unknown>[]>(() => [
    {
      accessorKey: 'name',
      header: 'Model',
      cell: ({ row }) => {
        const model = row.original;
        return (
          <div className="min-w-0">
            <div className="font-medium text-sm truncate max-w-45" title={model.name}>{model.name}</div>
            {model.modelName && model.modelName !== model.name && (
              <div className="text-xs text-(--theme-muted) font-mono truncate max-w-45">{model.modelName}</div>
            )}
          </div>
        );
      },
    },
    {
      accessorKey: 'provider',
      header: 'Provider',
      cell: ({ getValue }) => {
        const provider = getValue() as string;
        return (
          <span className="inline-flex items-center gap-1.5 text-xs font-mono">
            <ProviderIcon provider={provider} size={14} />
            {provider}
          </span>
        );
      },
    },
    {
      accessorKey: 'modelType',
      header: 'Type',
      cell: ({ getValue }) => {
        const type = getValue() as string | undefined;
        return <Badge variant="neutral" outline className="text-[10px]">{type || 'CHAT'}</Badge>;
      },
    },
    {
      id: 'status',
      header: 'Status',
      enableSorting: false,
      cell: ({ row }) => {
        const m = row.original;
        const tooltip = m.lastPingedAt ? `Last checked ${new Date(m.lastPingedAt).toLocaleString()}` : undefined;
        if (m.available === true) {
          return (
            <Badge variant="neutral" outline className="text-[10px] text-success border-success/30 bg-success/10" title={tooltip}>
              Live
            </Badge>
          );
        }
        if (m.available === false) {
          return (
            <Badge variant="neutral" outline className="text-[10px] text-warning border-warning/40 bg-warning/10" title={tooltip}>
              Unavailable
            </Badge>
          );
        }
        return <span className="text-xs text-(--theme-muted) italic">never tested</span>;
      },
    },
    {
      id: 'defaultSlots',
      header: 'Default Slot',
      enableSorting: false,
      cell: ({ row }) => {
        const slots = getActiveSlots(row.original.id);
        if (slots.length === 0) return <span className="text-xs text-(--theme-muted) italic">—</span>;
        return (
          <div className="flex flex-wrap gap-1">
            {slots.map(slot => (
              <Badge key={slot} variant="info" className="text-[10px]">{SLOT_LABELS[slot]}</Badge>
            ))}
          </div>
        );
      },
    },
    {
      accessorKey: 'agentCount',
      header: 'Agents',
      cell: ({ getValue }) => {
        const count = getValue() as number;
        return <span className="font-mono text-xs">{count}</span>;
      },
    },
    {
      accessorKey: 'runCount',
      header: 'Runs (30d)',
      cell: ({ getValue }) => {
        const count = getValue() as number;
        return count > 0
          ? <span className="font-mono text-xs">{count.toLocaleString()}</span>
          : <span className="text-xs text-(--theme-muted) italic">—</span>;
      },
    },
    {
      accessorKey: 'maxContextTokens',
      header: 'Context',
      cell: ({ getValue }) => {
        const tokens = getValue() as number | undefined;
        return tokens
          ? <span className="font-mono text-xs">{(tokens / 1000).toFixed(0)}K</span>
          : <span className="text-xs text-(--theme-muted) italic">—</span>;
      },
    },
    {
      accessorKey: 'rateLimitRpm',
      header: 'Rate Limit',
      cell: ({ getValue }) => {
        const rpm = getValue() as number | null | undefined;
        return rpm
          ? <span className="font-mono text-xs" title="§6 M-12 per-model RPM cap">{rpm.toLocaleString()}/min</span>
          : <span className="text-xs text-(--theme-muted) italic" title="No per-model gate; the global per-user filter still applies.">—</span>;
      },
    },
    {
      id: 'capabilities',
      header: 'Capabilities',
      enableSorting: false,
      cell: ({ row }) => {
        const m = row.original;
        return (
          <div className="flex items-center gap-3">
            <span className="flex items-center gap-1 text-[10px] text-(--theme-muted)" title="Tool Use">
              <CapabilityIcon supported={m.supportsTools} /> Tools
            </span>
            <span className="flex items-center gap-1 text-[10px] text-(--theme-muted)" title="Vision">
              <CapabilityIcon supported={m.supportsVision} /> Vision
            </span>
          </div>
        );
      },
    },
    {
      id: 'actions',
      header: '',
      enableSorting: false,
      cell: ({ row }) => {
        const model = row.original;
        return (
          <div className="flex items-center justify-end gap-1">
            <button
              type="button"
              onClick={() => handleEditClick(model)}
              aria-label="Edit model"
              title="Edit"
              className="btn btn-ghost btn-sm btn-square rounded-md text-(--theme-muted) hover:text-(--theme-foreground)"
            >
              <LuPencil className="w-4 h-4" />
            </button>
            <button
              type="button"
              onClick={() => handleTestClick(model)}
              disabled={pingingId === model.id}
              aria-label="Test connection"
              title={pingingId === model.id ? 'Testing…' : 'Test connection'}
              className="btn btn-ghost btn-sm btn-square rounded-md text-(--theme-muted) hover:text-info"
            >
              <LuRadio className={`w-4 h-4 ${pingingId === model.id ? 'animate-pulse' : ''}`} />
            </button>
            <button
              type="button"
              onClick={() => handleCloneClick(model)}
              aria-label="Clone model"
              title="Clone"
              className="btn btn-ghost btn-sm btn-square rounded-md text-(--theme-muted) hover:text-(--theme-foreground)"
            >
              <LuCopy className="w-4 h-4" />
            </button>
            <button
              type="button"
              onClick={() => handleExportClick(model)}
              aria-label="Export model JSON"
              title="Export JSON"
              className="btn btn-ghost btn-sm btn-square rounded-md text-(--theme-muted) hover:text-(--theme-foreground)"
            >
              <LuDownload className="w-4 h-4" />
            </button>
            <button
              type="button"
              onClick={() => { setDeleteError(null); setDeleteTarget(model); }}
              aria-label="Delete model"
              title="Delete"
              className="btn btn-ghost btn-sm btn-square rounded-md text-(--theme-muted) hover:text-error"
            >
              <LuTrash2 className="w-4 h-4" />
            </button>
          </div>
        );
      },
    },
  ], [currentSettings, pingingId]);

  return (
    <PageContainer variant="dashboard">
      <PageHeader
        icon={LuBox}
        title="Custom Models"
        subtitle="Manage language models and provider configurations."
        actions={
          <div className="flex items-center gap-2">
            <input
              ref={fileInputRef}
              type="file"
              accept="application/json,.json"
              onChange={handleImportFile}
              className="hidden"
              aria-hidden="true"
            />
            <Button size="sm" variant="ghost" onClick={handleImportClick} className="gap-1.5" title="Import a model JSON exported from another instance">
              <LuUpload className="w-4 h-4" /> Import
            </Button>
            <Button size="sm" onClick={handleCreateClick} className="gap-1.5">
              <LuPlus className="w-4 h-4" /> Custom Model
            </Button>
          </div>
        }
      />

      {error && <Alert severity="error" title="Error Loading Models">{error}</Alert>}

      {pingResult && (
        <Alert
          severity={pingResult.result.available ? 'success' : 'warning'}
          title={
            pingResult.result.available
              ? `${pingResult.name} is reachable (${pingResult.result.latencyMs} ms)`
              : `${pingResult.name} ping failed`
          }
        >
          {pingResult.result.available
            ? 'Provider responded successfully. The status badge has been refreshed.'
            : (pingResult.result.errorMessage || 'Provider did not respond within the call window.')}
        </Alert>
      )}

      {loading ? (
        <div className="space-y-2">
          {[1, 2, 3, 4].map(i => <div key={i} className="h-12 bg-obsidian-elevated/50 rounded-md animate-pulse" />)}
        </div>
      ) : (
        <DataTable
          columns={columns}
          data={models}
          manualPagination
          pageIndex={pageIndex}
          pageSize={PAGE_SIZE}
          totalElements={totalElements}
          onPageChange={setPageIndex}
          emptyMessage="No custom models configured. You are currently using system defaults."
        />
      )}

      <ModelFormModal
        isOpen={isFormModalOpen}
        onClose={() => setIsFormModalOpen(false)}
        onSave={handleSaveModel}
        model={selectedModelForEdit}
        currentSettings={currentSettings}
      />

      <Dialog
        isOpen={!!deleteTarget}
        setIsOpen={(open) => { if (!open) setDeleteTarget(null); }}
        title="Delete Model Configuration"
        content={
          deleteTarget
            ? deleteTarget.agentCount > 0
              ? `"${deleteTarget.name}" is currently used by ${deleteTarget.agentCount} agent(s). Deleting it will unassign those agents. Are you sure?`
              : `Are you sure you want to delete "${deleteTarget.name}"? This cannot be undone.`
            : ''
        }
        severity="error"
        confirmLabel="Delete"
        cancelLabel="Cancel"
        onConfirm={handleDeleteConfirm}
        onCancel={() => setDeleteTarget(null)}
      />

      {deleteError && (
        <Alert severity="error" title="Delete Failed">{deleteError}</Alert>
      )}
    </PageContainer>
  );
};
