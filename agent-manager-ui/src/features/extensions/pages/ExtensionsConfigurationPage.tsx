import React, { useState, useMemo, useEffect, useRef } from 'react';
import { useExtensions, useValidateExtension, useRegisterExtension, useDeleteExtension } from '../api/extensionApi';
import type { ExtensionRegistration } from '../api/extensionApi';
import type { ColumnDef } from '@tanstack/react-table';
import { Badge } from '../../../shared/components/ui/Badge';
import { NO_AUTOFILL } from '../../../shared/utils/noAutofill';
import { Button } from '../../../shared/components/ui/Button';
import { DataTable } from '../../../shared/components/ui/DataTable';
import { PageHeader } from '../../../shared/components/ui/PageHeader';
import { Dialog } from '../../../shared/components/ui/Dialog';
import { TemplatePickerGrid } from '../../../shared/components/ui/TemplatePickerGrid';
import type { TemplateCardItem } from '../../../shared/components/ui/TemplatePickerGrid';
import { useBlocker } from 'react-router-dom';
import {
    LuPuzzle, LuPlus, LuTrash2, LuCheck, LuX, LuWifi, LuArrowLeft,
} from 'react-icons/lu';
import { PageContainer } from '../../../shared/components/ui/PageContainer';

/**
 * Extension creation templates. One card per supported protocol shape (mirrors the
 * recommendation in `agm-obs-t005`-era design notes: keep the set narrow and protocol-
 * keyed, with a Blank option for power users). Form-side placeholders are template-
 * specific so the user gets concrete examples instead of one-size-fits-all hints.
 */
type ExtensionTemplate = {
  id: 'mcp' | 'webhook' | 'blank';
  icon: string;
  name: string;
  description: string;
  metadata?: string;
  defaults: Partial<ExtensionRegistration>;
  namePlaceholder: string;
  urlPlaceholder: string;
};

const EXTENSION_TEMPLATES: ExtensionTemplate[] = [
  {
    id: 'mcp',
    icon: '\u{1F50C}',
    name: 'MCP Server',
    description: 'Connect a Model Context Protocol server. Exposes tools, prompts, and resources to agents over HTTP.',
    metadata: 'Type: MCP',
    defaults: { type: 'MCP', active: true, name: '', url: '', transport: 'SSE' },
    namePlaceholder: 'Knowledge Base MCP',
    urlPlaceholder: 'http://mcp.internal:8080/mcp',
  },
  {
    id: 'webhook',
    icon: '\u{1FA9D}',
    name: 'HTTP Webhook',
    description: 'Out-of-process HTTP endpoint called by agent hooks for side effects, notifications, or async work.',
    metadata: 'Type: WEBHOOK',
    defaults: { type: 'WEBHOOK', active: true, name: '', url: '' },
    namePlaceholder: 'Python Analytics Hook',
    urlPlaceholder: 'http://host.docker.internal:8000/webhook',
  },
  {
    id: 'blank',
    icon: '\u{1F4E6}',
    name: 'Blank',
    description: 'Start from scratch with no presets. Pick the type and URL yourself.',
    defaults: { type: 'WEBHOOK', active: true, name: '', url: '' },
    namePlaceholder: 'My Extension',
    urlPlaceholder: 'http://your-service:port/path',
  },
];

const BLANK_TEMPLATE: ExtensionTemplate = EXTENSION_TEMPLATES[2];

export const ExtensionsConfigurationPage: React.FC = () => {
  const { data: extensions, isLoading, refetch } = useExtensions();
  const validateMutation = useValidateExtension();
  const registerMutation = useRegisterExtension();
  const deleteMutation = useDeleteExtension();
  const [deleteTarget, setDeleteTarget] = useState<ExtensionRegistration | null>(null);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  const [formData, setFormData] = useState<Partial<ExtensionRegistration>>({
    name: '',
    url: '',
    type: 'WEBHOOK',
    active: true
  });
  const [showForm, setShowForm] = useState(false);
  const [wizardStep, setWizardStep] = useState<'template' | 'form'>('template');
  const [activeTemplate, setActiveTemplate] = useState<ExtensionTemplate>(BLANK_TEMPLATE);
  const [dirty, setDirty] = useState(false);
  const [validationResult, setValidationResult] = useState<{success?: boolean, message?: string}>({});
  const [leaveOpen, setLeaveOpen] = useState(false);
  const pendingNavigate = useRef<(() => void) | null>(null);
  const blocker = useBlocker(dirty);

  const resetWizard = () => {
    setShowForm(false);
    setWizardStep('template');
    setActiveTemplate(BLANK_TEMPLATE);
    setFormData({ name: '', url: '', type: 'WEBHOOK', active: true });
    setValidationResult({});
    setDirty(false);
  };

  const handleTemplateSelect = (templateId: string) => {
    const tpl = EXTENSION_TEMPLATES.find(t => t.id === templateId) ?? BLANK_TEMPLATE;
    setActiveTemplate(tpl);
    setFormData(prev => ({ ...prev, ...tpl.defaults }));
    setValidationResult({});
    setWizardStep('form');
    // Choosing a template doesn't itself dirty the form — only when user types into a
    // field afterward. Avoids triggering the unsaved-changes blocker on accidental
    // template clicks.
  };

  useEffect(() => {
    if (blocker.state === 'blocked') {
      setLeaveOpen(true);
      pendingNavigate.current = () => blocker.proceed();
    }
  }, [blocker.state]);

  const handleValidate = async () => {
    if (!formData.url || !formData.type) return;
    try {
      const res = await validateMutation.mutateAsync({
        url: formData.url,
        type: formData.type
      });
      setValidationResult(res);
    } catch (e: any) {
      setValidationResult({ success: false, message: e.message || 'Validation failed. External service unavailable.' });
    }
  };

  const handleSave = async () => {
    if (!formData.name || !formData.url) return;
    try {
      await registerMutation.mutateAsync(formData as ExtensionRegistration);
      resetWizard();
      refetch();
    } catch (e: any) {
      console.error(e);
    }
  };

  // ── Column Definitions ──────────────────────────────────────
  const columns = useMemo<ColumnDef<ExtensionRegistration, unknown>[]>(() => [
    {
      accessorKey: 'name',
      header: 'Extension',
      cell: ({ row }) => {
        const ext = row.original;
        return (
          <div className="flex items-center gap-3 min-w-0">
            <LuPuzzle className="w-4 h-4 shrink-0 text-(--theme-muted)" />
            <span className="font-medium text-sm truncate max-w-45" title={ext.name}>{ext.name}</span>
          </div>
        );
      },
    },
    {
      accessorKey: 'type',
      header: 'Type',
      cell: ({ getValue }) => {
        const type = getValue() as string;
        const variant = type === 'MCP' ? 'primary' : 'neutral';
        return <Badge variant={variant} outline className="text-xs font-mono">{type}</Badge>;
      },
    },
    {
      accessorKey: 'url',
      header: 'Endpoint URL',
      cell: ({ getValue }) => (
        <code className="text-xs font-mono text-(--theme-muted) bg-obsidian-elevated px-2 py-0.5 rounded break-all max-w-75 block truncate" title={getValue() as string}>
          {getValue() as string}
        </code>
      ),
    },
    {
      accessorKey: 'active',
      header: 'Status',
      cell: ({ getValue }) => {
        const active = getValue() as boolean;
        return active
          ? <Badge variant="success" outline className="text-xs gap-1"><LuCheck className="w-3 h-3" /> Active</Badge>
          : <Badge variant="error" outline className="text-xs gap-1"><LuX className="w-3 h-3" /> Inactive</Badge>;
      },
    },
    {
      id: 'actions',
      header: '',
      enableSorting: false,
      cell: ({ row }) => {
        const ext = row.original;
        const id = ext.id;
        return (
          <div className="flex items-center justify-end gap-1">
            <button
              type="button"
              disabled={!id}
              onClick={() => {
                if (id) {
                  setDeleteError(null);
                  setDeleteTarget(ext);
                }
              }}
              aria-label="Deregister extension"
              title="Deregister"
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
        icon={LuPuzzle}
        title="Extensions"
        subtitle="Register external Python/JS services (MCP & Webhooks) through secure backend proxy."
        actions={
          showForm ? null : (
            <Button size="sm" className="gap-1.5" onClick={() => { setShowForm(true); setWizardStep('template'); }}>
              <LuPlus className="w-4 h-4" /> Register Extension
            </Button>
          )
        }
      />

      {/* ─── Wizard: template picker ─────────────────────────── */}
      {showForm && wizardStep === 'template' && (
        <div className="bg-(--theme-card) rounded-xl border border-(--theme-muted)/10 p-6 space-y-4 shadow-sm animate-in fade-in duration-200">
          <div className="flex items-center justify-between">
            <h3 className="text-sm font-bold uppercase tracking-wider text-(--theme-muted)">
              Choose an Extension Template
            </h3>
            <Button variant="ghost" size="sm" onClick={resetWizard}>Cancel</Button>
          </div>
          <p className="text-xs text-(--theme-muted) leading-relaxed">
            Pick a protocol shape to pre-fill the registration form, or start blank.
          </p>
          <TemplatePickerGrid
            items={EXTENSION_TEMPLATES.map((tpl): TemplateCardItem => ({
              id: tpl.id,
              icon: tpl.icon,
              name: tpl.name,
              description: tpl.description,
              metadata: tpl.metadata,
            }))}
            onSelect={handleTemplateSelect}
          />
        </div>
      )}

      {/* ─── Wizard: registration form (post-template-select) ─── */}
      {showForm && wizardStep === 'form' && (
        <div className="bg-(--theme-card) rounded-xl border border-(--theme-muted)/10 p-6 space-y-4 shadow-sm animate-in fade-in duration-200">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <Button variant="ghost" size="sm" className="gap-1.5" onClick={() => { setWizardStep('template'); setDirty(false); }}>
                <LuArrowLeft className="w-3.5 h-3.5" /> Templates
              </Button>
              <h3 className="text-sm font-bold uppercase tracking-wider text-(--theme-muted)">
                {activeTemplate.id === 'blank' ? 'Register New Extension' : `Register: ${activeTemplate.name}`}
              </h3>
            </div>
          </div>
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            <div>
              <label className="label"><span className="label-text text-xs font-medium">Name</span></label>
              <input
                type="text"
                className="input input-bordered input-sm w-full"
                value={formData.name}
                onChange={(e) => { setFormData({ ...formData, name: e.target.value }); setDirty(true); }}
                placeholder={activeTemplate.namePlaceholder}
              />
            </div>
            <div>
              <label className="label"><span className="label-text text-xs font-medium">Type</span></label>
              <select
                className="select select-bordered select-sm w-full"
                value={formData.type}
                onChange={(e) => { setFormData({ ...formData, type: e.target.value as 'MCP' | 'WEBHOOK' }); setDirty(true); }}
              >
                <option value="WEBHOOK">Webhook (out-of-process)</option>
                <option value="MCP">MCP Server</option>
              </select>
            </div>
            <div>
              <label className="label"><span className="label-text text-xs font-medium text-warn-amber">Internal VPC URL</span></label>
              <input
                type="text"
                className="input input-bordered input-sm w-full font-mono text-xs"
                value={formData.url}
                onChange={(e) => { setFormData({ ...formData, url: e.target.value }); setDirty(true); }}
                placeholder={activeTemplate.urlPlaceholder}
              />
            </div>
          </div>

          {/* ─── MCP-specific: transport + outbound auth ─────────── */}
          {formData.type === 'MCP' && (
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              <div>
                <label className="label"><span className="label-text text-xs font-medium">Transport</span></label>
                <select
                  className="select select-bordered select-sm w-full"
                  value={formData.transport ?? 'SSE'}
                  onChange={(e) => { setFormData({ ...formData, transport: e.target.value as 'SSE' | 'STREAMABLE_HTTP' }); setDirty(true); }}
                >
                  <option value="SSE">SSE (Server-Sent Events)</option>
                  <option value="STREAMABLE_HTTP">Streamable HTTP</option>
                </select>
              </div>
              <div>
                <label className="label">
                  <span className="label-text text-xs font-medium">Auth Token</span>
                  <span className="label-text-alt text-xs text-(--theme-muted)">
                    {formData.authPreview ? `current: ${formData.authPreview}` : 'optional'}
                  </span>
                </label>
                <input
                  type="password"
                  {...NO_AUTOFILL}
                  className="input input-bordered input-sm w-full font-mono text-xs"
                  value={formData.auth ?? ''}
                  onChange={(e) => { setFormData({ ...formData, auth: e.target.value }); setDirty(true); }}
                  placeholder="Bearer token, e.g. a GitHub PAT (sent as Authorization header)"
                />
              </div>
            </div>
          )}

          <div className="flex items-center gap-3 pt-2">
            <Button
              variant="outline"
              size="sm"
              className="gap-1.5"
              onClick={handleValidate}
              disabled={validateMutation.isPending || !formData.url}
            >
              {validateMutation.isPending
                ? <span className="loading loading-spinner loading-sm"></span>
                : <LuWifi className="w-3.5 h-3.5" />
              }
              Ping Connection
            </Button>

            {validationResult.message && (
              <span className={`text-xs font-medium ${validationResult.success ? 'text-active-green' : 'text-error-red'}`}>
                {validationResult.message}
              </span>
            )}

            <div className="ml-auto flex gap-2">
              <Button variant="ghost" size="sm" onClick={resetWizard}>
                Cancel
              </Button>
              <Button
                size="sm"
                onClick={handleSave}
                disabled={registerMutation.isPending || !formData.url || !formData.name}
              >
                Save Registration
              </Button>
            </div>
          </div>
        </div>
      )}

      {/* Extensions DataTable */}
      {isLoading ? (
        <div className="space-y-2">
          {[1, 2, 3].map(i => <div key={i} className="h-12 bg-obsidian-elevated/50 rounded-md animate-pulse" />)}
        </div>
      ) : (
        <DataTable
          columns={columns}
          data={extensions || []}
          enablePagination
          defaultPageSize={20}
          emptyMessage="No extensions registered yet. Register an MCP server or Python Webhook to begin."
        />
      )}
      <Dialog
        isOpen={leaveOpen}
        setIsOpen={(open) => {
          setLeaveOpen(open);
          if (!open && blocker.state === 'blocked') blocker.reset();
        }}
        title="Unsaved Changes"
        content="You have unsaved changes. Are you sure you want to leave? Changes will be lost."
        severity="warning"
        confirmLabel="Leave"
        cancelLabel="Stay"
        onConfirm={() => {
          setLeaveOpen(false);
          setDirty(false);
          pendingNavigate.current?.();
        }}
        onCancel={() => {
          setLeaveOpen(false);
          if (blocker.state === 'blocked') blocker.reset();
        }}
      />
      <Dialog
        isOpen={deleteTarget !== null}
        setIsOpen={(open) => {
          if (!open) {
            setDeleteTarget(null);
            setDeleteError(null);
          }
        }}
        title="Deregister extension"
        content={
          deleteError
            ? `Failed to deregister "${deleteTarget?.name}": ${deleteError}`
            : `Deregister "${deleteTarget?.name}"? Agents using this ${deleteTarget?.type === 'MCP' ? 'MCP server' : 'webhook'} will lose access immediately.`
        }
        severity="error"
        confirmLabel={deleteMutation.isPending ? 'Deregistering…' : 'Deregister'}
        cancelLabel="Cancel"
        shouldCloseOnConfirm={false}
        onConfirm={async () => {
          if (!deleteTarget?.id) return;
          try {
            await deleteMutation.mutateAsync(deleteTarget.id);
            setDeleteTarget(null);
            setDeleteError(null);
          } catch (e: unknown) {
            setDeleteError(e instanceof Error ? e.message : 'Unknown error');
          }
        }}
        onCancel={() => {
          setDeleteTarget(null);
          setDeleteError(null);
        }}
      />
    </PageContainer>
  );
};
