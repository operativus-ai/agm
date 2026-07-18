import React, { useEffect, useMemo, useState } from 'react';
import type { ColumnDef } from '@tanstack/react-table';
import type { AlertIntegration, AlertIntegrationRequest } from '../api/alertIntegrationApi';
import { AlertIntegrationApi } from '../api/alertIntegrationApi';
import { PageHeader } from '../../../shared/components/ui/PageHeader';
import { Alert } from '../../../shared/components/ui/Alert';
import { Badge } from '../../../shared/components/ui/Badge';
import { Button } from '../../../shared/components/ui/Button';
import { DataTable } from '../../../shared/components/ui/DataTable';
import { PageContainer } from '../../../shared/components/ui/PageContainer';
import { Dialog } from '../../../shared/components/ui/Dialog';
import { LuBell, LuPlus, LuPencil, LuTrash2, LuSend } from 'react-icons/lu';

const EMPTY_FORM: AlertIntegrationRequest = { name: '', type: 'WEBHOOK', endpointUrl: '', enabled: true };

export const AlertIntegrationsPage: React.FC = () => {
    const [integrations, setIntegrations] = useState<AlertIntegration[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [message, setMessage] = useState<{ text: string; type: 'success' | 'error' } | null>(null);

    const [modalOpen, setModalOpen] = useState(false);
    const [editing, setEditing] = useState<AlertIntegration | null>(null);
    const [form, setForm] = useState<AlertIntegrationRequest>(EMPTY_FORM);
    const [saving, setSaving] = useState(false);
    /** id of the integration whose test fire is in flight; locks the action until response. */
    const [testingId, setTestingId] = useState<string | null>(null);

    const load = async () => {
        try {
            setLoading(true);
            setIntegrations(await AlertIntegrationApi.list());
            setError(null);
        } catch (err) {
            setError(err instanceof Error ? err.message : 'Failed to load integrations');
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => { load(); }, []);

    const showMsg = (text: string, type: 'success' | 'error' = 'success') => {
        setMessage({ text, type });
        setTimeout(() => setMessage(null), 4000);
    };

    const openCreate = () => { setEditing(null); setForm(EMPTY_FORM); setModalOpen(true); };
    const openEdit = (i: AlertIntegration) => {
        setEditing(i);
        setForm({ name: i.name, type: i.type, endpointUrl: i.endpointUrl, enabled: i.enabled });
        setModalOpen(true);
    };

    const handleSave = async () => {
        try {
            setSaving(true);
            if (editing) {
                await AlertIntegrationApi.update(editing.id, form);
                showMsg('Integration updated');
            } else {
                await AlertIntegrationApi.create(form);
                showMsg('Integration created');
            }
            setModalOpen(false);
            await load();
        } catch (err) {
            showMsg(err instanceof Error ? err.message : 'Save failed', 'error');
        } finally {
            setSaving(false);
        }
    };

    /** §4 P5 T040 — fires a synthetic AlertFiredEvent through the integration's webhook
     *  so operators can verify a fresh URL or credential without waiting for a real
     *  alert. Backend always returns 200 unless the id is unknown; a delivery failure
     *  surfaces in the response body's {@code delivered=false} + {@code message} fields. */
    const handleTestFire = async (id: string, name: string) => {
        setTestingId(id);
        try {
            const result = await AlertIntegrationApi.testFire(id);
            if (result.delivered) {
                showMsg(`${name}: ${result.message}`, 'success');
            } else {
                showMsg(`${name}: ${result.message}`, 'error');
            }
        } catch (err) {
            showMsg(`${name}: ${err instanceof Error ? err.message : 'Test failed'}`, 'error');
        } finally {
            setTestingId(null);
        }
    };

    const handleDelete = async (id: string, name: string) => {
        if (!confirm(`Delete integration "${name}"?`)) return;
        try {
            await AlertIntegrationApi.remove(id);
            showMsg('Integration deleted');
            await load();
        } catch (err) {
            showMsg('Delete failed', 'error');
        }
    };

    const columns = useMemo<ColumnDef<AlertIntegration>[]>(() => [
        {
            accessorKey: 'name',
            header: 'Name',
            cell: ({ getValue }) => <span className="font-medium text-sm text-(--theme-foreground)">{getValue() as string}</span>,
        },
        {
            accessorKey: 'type',
            header: 'Type',
            cell: ({ getValue }) => <Badge variant="ghost" className="text-xs font-mono">{getValue() as string}</Badge>,
        },
        {
            accessorKey: 'endpointUrl',
            header: 'Endpoint',
            cell: ({ getValue }) => (
                <span className="text-xs font-mono text-(--theme-muted) truncate block max-w-xs">{getValue() as string}</span>
            ),
        },
        {
            accessorKey: 'enabled',
            header: 'Status',
            cell: ({ getValue }) => {
                const enabled = getValue() as boolean;
                return (
                    <div className="flex items-center gap-2">
                        <div className={`w-2 h-2 rounded-full ${enabled ? 'bg-success' : 'bg-error'}`} />
                        <span className={`text-xs font-medium ${enabled ? 'text-success' : 'text-error'}`}>
                            {enabled ? 'Active' : 'Disabled'}
                        </span>
                    </div>
                );
            },
        },
        {
            accessorKey: 'createdAt',
            header: 'Created',
            cell: ({ getValue }) => (
                <span className="text-xs text-(--theme-muted)">
                    {getValue() ? new Date(getValue() as string).toLocaleDateString() : '—'}
                </span>
            ),
        },
        {
            id: 'actions',
            header: '',
            enableSorting: false,
            cell: ({ row }) => (
                <div className="flex items-center justify-end gap-1">
                    <Button
                        size="sm"
                        variant="ghost"
                        title="Send test alert"
                        className="px-2 text-(--theme-muted) hover:text-primary"
                        disabled={testingId === row.original.id}
                        onClick={() => handleTestFire(row.original.id, row.original.name)}
                    >
                        <LuSend className="w-3.5 h-3.5" />
                    </Button>
                    <Button size="sm" variant="ghost" title="Edit" className="px-2 text-(--theme-muted) hover:text-primary" onClick={() => openEdit(row.original)}>
                        <LuPencil className="w-3.5 h-3.5" />
                    </Button>
                    <Button size="sm" variant="ghost" title="Delete" className="px-2 text-(--theme-muted) hover:text-error" onClick={() => handleDelete(row.original.id, row.original.name)}>
                        <LuTrash2 className="w-3.5 h-3.5" />
                    </Button>
                </div>
            ),
        },
    ], [testingId]);

    return (
        <PageContainer variant="dashboard">
            <PageHeader
                icon={LuBell}
                title="Alert Integrations"
                subtitle="Configure outbound webhook and Slack notification channels for fired alerts."
                actions={
                    <Button size="sm" onClick={openCreate} className="gap-1.5">
                        <LuPlus className="w-4 h-4" /> Add Integration
                    </Button>
                }
            />

            {!loading && !integrations.some(i => i.enabled) && (
                <Alert severity="warning" className="mb-4">
                    No enabled alert integrations — SLA breaches and fired alerts will not notify anyone. Add an integration above.
                </Alert>
            )}
            {message && (
                <Alert severity={message.type === 'success' ? 'success' : 'error'} className="mb-4">
                    {message.text}
                </Alert>
            )}
            {error && <Alert severity="error" className="mb-4">{error}</Alert>}

            <DataTable
                columns={columns}
                data={integrations}
                enablePagination
                defaultPageSize={25}
                emptyMessage="No alert integrations configured."
            />

            <Dialog
                isOpen={modalOpen}
                setIsOpen={setModalOpen}
                title={editing ? 'Edit Integration' : 'Add Integration'}
            >
                <div className="flex flex-col gap-4 py-2">
                    <div className="flex flex-col gap-1">
                        <label className="text-xs text-(--theme-muted) font-medium">Name</label>
                        <input
                            className="input input-bordered input-sm bg-obsidian-surface border-obsidian-stroke text-(--theme-foreground) w-full"
                            value={form.name}
                            onChange={e => setForm(f => ({ ...f, name: e.target.value }))}
                            placeholder="My Webhook"
                        />
                    </div>
                    <div className="flex flex-col gap-1">
                        <label className="text-xs text-(--theme-muted) font-medium">Type</label>
                        <select
                            className="select select-bordered select-sm bg-obsidian-surface border-obsidian-stroke text-(--theme-foreground) w-full"
                            value={form.type}
                            onChange={e => setForm(f => ({ ...f, type: e.target.value as 'WEBHOOK' | 'SLACK' | 'PAGERDUTY' }))}
                        >
                            <option value="WEBHOOK">Webhook</option>
                            <option value="SLACK">Slack</option>
                            <option value="PAGERDUTY">PagerDuty</option>
                        </select>
                    </div>
                    <div className="flex flex-col gap-1">
                        <label className="text-xs text-(--theme-muted) font-medium">
                            {form.type === 'PAGERDUTY' ? 'Routing Key' : 'Endpoint URL'}
                        </label>
                        <input
                            className="input input-bordered input-sm bg-obsidian-surface border-obsidian-stroke text-(--theme-foreground) w-full"
                            value={form.endpointUrl}
                            onChange={e => setForm(f => ({ ...f, endpointUrl: e.target.value }))}
                            placeholder={
                                form.type === 'PAGERDUTY'
                                    ? '32-char integration key from your PagerDuty service'
                                    : 'https://hooks.slack.com/...'
                            }
                        />
                        {form.type === 'PAGERDUTY' && (
                            <p className="text-[11px] text-(--theme-muted)">
                                AGM POSTs to <code className="text-[10px]">https://events.pagerduty.com/v2/enqueue</code> with this routing key embedded in the body. Severity maps INFO→info, WARNING→warning, CRITICAL→critical, ERROR→error.
                            </p>
                        )}
                    </div>
                    <div className="flex items-center gap-3">
                        <input
                            type="checkbox"
                            className="checkbox checkbox-sm"
                            checked={form.enabled}
                            onChange={e => setForm(f => ({ ...f, enabled: e.target.checked }))}
                            id="integration-enabled"
                        />
                        <label htmlFor="integration-enabled" className="text-sm text-(--theme-foreground)">Enabled</label>
                    </div>
                    <div className="flex justify-end gap-2 pt-2">
                        <Button variant="ghost" size="sm" onClick={() => setModalOpen(false)}>Cancel</Button>
                        <Button size="sm" onClick={handleSave} disabled={saving || !form.name || !form.endpointUrl}>
                            {saving ? 'Saving...' : editing ? 'Save Changes' : 'Add Integration'}
                        </Button>
                    </div>
                </div>
            </Dialog>
        </PageContainer>
    );
};
