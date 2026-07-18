import React, { useEffect, useMemo, useState } from 'react';
import type { ColumnDef } from '@tanstack/react-table';
import type { PaginatedResponse } from '../../../shared/types/api';
import type { AuditLogEntry, SystemAuditLogEntry } from '../api/auditLogApi';
import { AuditLogApi } from '../api/auditLogApi';
import { PageHeader } from '../../../shared/components/ui/PageHeader';
import { Alert } from '../../../shared/components/ui/Alert';
import { Badge } from '../../../shared/components/ui/Badge';
import { Button } from '../../../shared/components/ui/Button';
import { DataTable } from '../../../shared/components/ui/DataTable';
import { Dialog } from '../../../shared/components/ui/Dialog';
import { PageContainer } from '../../../shared/components/ui/PageContainer';
import { Select } from '../../../shared/components/ui/Select';
import { LuDownload, LuEye } from 'react-icons/lu';

const ACTION_VARIANTS: Record<string, 'success' | 'error' | 'warning' | 'info'> = {
    CREATE: 'success',
    DELETE: 'error',
    UPDATE: 'info',
    ROLLBACK: 'warning',
};

const SYSTEM_ACTION_VARIANTS: Record<string, 'success' | 'error' | 'warning' | 'info'> = {
    LOGIN_SUCCESS: 'success',
    LOGIN_FAILURE: 'error',
    LOGOUT: 'info',
    CREATE: 'success',
    UPDATE: 'info',
    DELETE: 'error',
};

const httpStatusVariant = (status?: number): 'success' | 'error' | 'warning' | 'info' => {
    if (!status) return 'info';
    if (status >= 500) return 'error';
    if (status >= 400) return 'warning';
    return 'success';
};

export const AuditLogPage: React.FC = () => {
    const [activeTab, setActiveTab] = useState<'agent' | 'system'>('agent');

    // Agent audit log state
    const [logsPage, setLogsPage] = useState<PaginatedResponse<AuditLogEntry> | null>(null);
    const [, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [pageIndex, setPageIndex] = useState(0);
    const [filterUsername, setFilterUsername] = useState('');
    const [filterAction, setFilterAction] = useState('');
    const [filterAgentId, setFilterAgentId] = useState('');

    // System audit log state
    const [sysLogsPage, setSysLogsPage] = useState<PaginatedResponse<SystemAuditLogEntry> | null>(null);
    const [, setSysLoading] = useState(true);
    const [sysError, setSysError] = useState<string | null>(null);
    const [sysPageIndex, setSysPageIndex] = useState(0);
    const [sysFilterUsername, setSysFilterUsername] = useState('');
    const [sysFilterAction, setSysFilterAction] = useState('');
    const [sysFilterResourceType, setSysFilterResourceType] = useState('');
    const [sysFilterResourceId, setSysFilterResourceId] = useState('');

    const PAGE_SIZE = 50;

    const loadLogs = async () => {
        try {
            setLoading(true);
            const data = await AuditLogApi.listAuditLogs({
                username: filterUsername || undefined,
                action: filterAction || undefined,
                agentId: filterAgentId || undefined,
                page: pageIndex,
                size: PAGE_SIZE,
            });
            setLogsPage(data);
            setError(null);
        } catch (err) {
            setError(err instanceof Error ? err.message : 'Error loading audit logs');
        } finally {
            setLoading(false);
        }
    };

    const loadSystemLogs = async () => {
        try {
            setSysLoading(true);
            const data = await AuditLogApi.listSystemAuditLogs({
                username: sysFilterUsername || undefined,
                action: sysFilterAction || undefined,
                resourceType: sysFilterResourceType || undefined,
                resourceId: sysFilterResourceId || undefined,
                page: sysPageIndex,
                size: PAGE_SIZE,
            });
            setSysLogsPage(data);
            setSysError(null);
        } catch (err) {
            setSysError(err instanceof Error ? err.message : 'Error loading system audit logs');
        } finally {
            setSysLoading(false);
        }
    };

    useEffect(() => { loadLogs(); }, [pageIndex, filterUsername, filterAction, filterAgentId]);
    useEffect(() => { loadSystemLogs(); }, [sysPageIndex, sysFilterUsername, sysFilterAction, sysFilterResourceType, sysFilterResourceId]);

    const [isExporting, setIsExporting] = useState(false);

    /** §4 T012 — row-level drill-down. Holds the full AuditLogEntry whose details are
     *  being inspected; null = modal closed. The details modal renders the changeset
     *  JSON in full (the table cell only shows a 120-char preview). */
    const [detailLog, setDetailLog] = useState<AuditLogEntry | null>(null);

    /** Pretty-print a JSON string for display. Falls back to the raw text on parse
     *  failure so legacy or hand-written rows still render readably. */
    const formatChangeset = (raw: string | null | undefined): string => {
        if (!raw) return '(no changeset recorded)';
        try {
            return JSON.stringify(JSON.parse(raw), null, 2);
        } catch {
            return raw;
        }
    };

    /** §4 T013 — operator-fired CSV download of the agent audit log, scoped by the
     *  same filter set as the table view. Backend caps at 10K rows; on overflow the
     *  operator should narrow filters. We bypass ApiClient because that hard-codes
     *  JSON; CSV needs the raw text body. */
    const handleExportCsv = async () => {
        setIsExporting(true);
        try {
            const csv = await AuditLogApi.exportAuditLogsCsv({
                username: filterUsername || undefined,
                action: filterAction || undefined,
                agentId: filterAgentId || undefined,
            });
            const blob = new Blob([csv], { type: 'text/csv' });
            const href = URL.createObjectURL(blob);
            const link = document.createElement('a');
            link.href = href;
            link.download = `audit-logs-${new Date().toISOString().slice(0, 10)}.csv`;
            document.body.appendChild(link);
            link.click();
            document.body.removeChild(link);
            URL.revokeObjectURL(href);
            setError(null);
        } catch (err) {
            setError(err instanceof Error ? err.message : 'Export failed');
        } finally {
            setIsExporting(false);
        }
    };

    const agentColumns = useMemo<ColumnDef<AuditLogEntry>[]>(() => [
        {
            accessorKey: 'createdAt',
            header: 'Timestamp',
            cell: ({ row }) => (
                <span className="text-xs font-mono text-theme-muted">
                    {new Date(row.original.createdAt).toLocaleString()}
                </span>
            ),
        },
        {
            accessorKey: 'action',
            header: 'Action',
            cell: ({ row }) => (
                <Badge variant={ACTION_VARIANTS[row.original.action] ?? 'info'} size="sm">
                    {row.original.action}
                </Badge>
            ),
        },
        {
            accessorKey: 'username',
            header: 'User',
            cell: ({ row }) => (
                <span className="text-sm font-mono text-theme-foreground">{row.original.username ?? '—'}</span>
            ),
        },
        {
            accessorKey: 'agentId',
            header: 'Agent ID',
            cell: ({ row }) => (
                <span className="text-xs font-mono text-theme-muted truncate max-w-xs block">{row.original.agentId}</span>
            ),
        },
        {
            accessorKey: 'versionNumber',
            header: 'Version',
            cell: ({ row }) => (
                <span className="text-xs text-theme-muted">{row.original.versionNumber != null ? `v${row.original.versionNumber}` : '—'}</span>
            ),
        },
        {
            accessorKey: 'changeset',
            header: 'Changes',
            cell: ({ row }) => (
                <pre className="text-xs text-theme-muted max-w-sm truncate whitespace-pre-wrap">
                    {row.original.changeset ? JSON.stringify(JSON.parse(row.original.changeset), null, 2).slice(0, 120) : '—'}
                </pre>
            ),
        },
        {
            id: 'details',
            header: '',
            enableSorting: false,
            cell: ({ row }) => (
                <Button
                    size="sm"
                    variant="ghost"
                    title="View full audit entry"
                    onClick={() => setDetailLog(row.original)}
                >
                    <LuEye className="w-3.5 h-3.5" />
                </Button>
            ),
        },
    ], []);

    const sysColumns = useMemo<ColumnDef<SystemAuditLogEntry>[]>(() => [
        {
            accessorKey: 'createdAt',
            header: 'Timestamp',
            cell: ({ row }) => (
                <span className="text-xs font-mono text-theme-muted">
                    {new Date(row.original.createdAt).toLocaleString()}
                </span>
            ),
        },
        {
            accessorKey: 'action',
            header: 'Action',
            cell: ({ row }) => (
                <Badge variant={SYSTEM_ACTION_VARIANTS[row.original.action] ?? 'info'} size="sm">
                    {row.original.action}
                </Badge>
            ),
        },
        {
            accessorKey: 'username',
            header: 'User',
            cell: ({ row }) => (
                <span className="text-sm font-mono text-theme-foreground">{row.original.username ?? '—'}</span>
            ),
        },
        {
            id: 'resource',
            header: 'Resource',
            cell: ({ row }) => (
                <div className="flex flex-col">
                    <span className="text-xs font-semibold text-theme-foreground">{row.original.resourceType}</span>
                    {row.original.resourceId && (
                        <span className="text-xs font-mono text-theme-muted truncate max-w-xs">{row.original.resourceId}</span>
                    )}
                </div>
            ),
        },
        {
            id: 'path',
            header: 'Path',
            cell: ({ row }) => (
                <div className="flex items-center gap-1.5">
                    {row.original.httpMethod && (
                        <span className="text-xs font-bold font-mono text-theme-muted">{row.original.httpMethod}</span>
                    )}
                    {row.original.requestPath && (
                        <span className="text-xs font-mono text-theme-muted truncate max-w-xs">{row.original.requestPath}</span>
                    )}
                    {!row.original.httpMethod && !row.original.requestPath && '—'}
                </div>
            ),
        },
        {
            accessorKey: 'responseStatus',
            header: 'Status',
            cell: ({ row }) => row.original.responseStatus != null ? (
                <Badge variant={httpStatusVariant(row.original.responseStatus)} size="sm">
                    {row.original.responseStatus}
                </Badge>
            ) : <span className="text-xs text-theme-muted">—</span>,
        },
    ], []);

    return (
        <PageContainer variant="dashboard">
            <PageHeader
                title="Audit Log"
                subtitle="Immutable record of all agent configuration changes and system events."
                actions={
                    activeTab === 'agent' ? (
                        <Button
                            size="sm"
                            variant="ghost"
                            className="gap-1.5"
                            onClick={handleExportCsv}
                            disabled={isExporting}
                            title="Download the filtered agent audit log as CSV (capped at 10K rows)"
                        >
                            <LuDownload className="w-4 h-4" />
                            {isExporting ? 'Exporting…' : 'Export CSV'}
                        </Button>
                    ) : undefined
                }
            />

            <div className="flex gap-1 mb-4 border-b border-obsidian-stroke">
                <button
                    className={`px-4 py-2 text-sm font-medium border-b-2 transition-colors ${
                        activeTab === 'agent'
                            ? 'border-primary text-primary'
                            : 'border-transparent text-theme-muted hover:text-theme-foreground'
                    }`}
                    onClick={() => setActiveTab('agent')}
                >
                    Agent Changes
                </button>
                <button
                    className={`px-4 py-2 text-sm font-medium border-b-2 transition-colors ${
                        activeTab === 'system'
                            ? 'border-primary text-primary'
                            : 'border-transparent text-theme-muted hover:text-theme-foreground'
                    }`}
                    onClick={() => setActiveTab('system')}
                >
                    System Events
                </button>
            </div>

            {activeTab === 'agent' && (
                <>
                    {error && <Alert severity="error" className="mb-4">{error}</Alert>}
                    <div className="flex flex-wrap gap-3 mb-4">
                        <input
                            type="text"
                            className="input input-bordered input-sm bg-obsidian-surface border-obsidian-stroke text-theme-foreground w-48"
                            placeholder="Filter by user..."
                            value={filterUsername}
                            onChange={e => { setFilterUsername(e.target.value); setPageIndex(0); }}
                        />
                        <Select
                            value={filterAction || '__all__'}
                            onValueChange={v => { setFilterAction(v === '__all__' ? '' : v); setPageIndex(0); }}
                            options={[
                                { value: '__all__', label: 'All Actions' },
                                ...Object.keys(ACTION_VARIANTS).map(k => ({ value: k, label: k })),
                            ]}
                        />
                        <input
                            type="text"
                            className="input input-bordered input-sm bg-obsidian-surface border-obsidian-stroke text-theme-foreground w-56"
                            placeholder="Filter by agent ID..."
                            value={filterAgentId}
                            onChange={e => { setFilterAgentId(e.target.value); setPageIndex(0); }}
                        />
                    </div>
                    <DataTable
                        columns={agentColumns}
                        data={logsPage?.content ?? []}
                        manualPagination={true}
                        pageIndex={pageIndex}
                        pageSize={PAGE_SIZE}
                        totalElements={logsPage?.page.totalElements ?? 0}
                        onPageChange={setPageIndex}
                    />
                </>
            )}

            {activeTab === 'system' && (
                <>
                    {sysError && <Alert severity="error" className="mb-4">{sysError}</Alert>}
                    <div className="flex flex-wrap gap-3 mb-4">
                        <input
                            type="text"
                            className="input input-bordered input-sm bg-obsidian-surface border-obsidian-stroke text-theme-foreground w-48"
                            placeholder="Filter by user..."
                            value={sysFilterUsername}
                            onChange={e => { setSysFilterUsername(e.target.value); setSysPageIndex(0); }}
                        />
                        <Select
                            value={sysFilterAction || '__all__'}
                            onValueChange={v => { setSysFilterAction(v === '__all__' ? '' : v); setSysPageIndex(0); }}
                            options={[
                                { value: '__all__', label: 'All Actions' },
                                ...Object.keys(SYSTEM_ACTION_VARIANTS).map(k => ({ value: k, label: k })),
                            ]}
                        />
                        <input
                            type="text"
                            className="input input-bordered input-sm bg-obsidian-surface border-obsidian-stroke text-theme-foreground w-48"
                            placeholder="Filter by resource type..."
                            value={sysFilterResourceType}
                            onChange={e => { setSysFilterResourceType(e.target.value); setSysPageIndex(0); }}
                        />
                        <input
                            type="text"
                            className="input input-bordered input-sm bg-obsidian-surface border-obsidian-stroke text-theme-foreground w-56"
                            placeholder="Filter by resource ID..."
                            value={sysFilterResourceId}
                            onChange={e => { setSysFilterResourceId(e.target.value); setSysPageIndex(0); }}
                        />
                    </div>
                    <DataTable
                        columns={sysColumns}
                        data={sysLogsPage?.content ?? []}
                        manualPagination={true}
                        pageIndex={sysPageIndex}
                        pageSize={PAGE_SIZE}
                        totalElements={sysLogsPage?.page.totalElements ?? 0}
                        onPageChange={setSysPageIndex}
                    />
                </>
            )}

            <Dialog
                isOpen={detailLog !== null}
                setIsOpen={(open) => { if (!open) setDetailLog(null); }}
                title={detailLog ? `${detailLog.action} — Agent ${detailLog.agentId}` : 'Audit Detail'}
                canBeCanceled={false}
                confirmLabel="Close"
                onConfirm={() => setDetailLog(null)}
                className="max-w-3xl"
            >
                {detailLog && (
                    <div className="space-y-3 text-sm">
                        <div className="grid grid-cols-2 gap-3">
                            <div>
                                <div className="text-[10px] uppercase tracking-wide text-theme-muted">Timestamp</div>
                                <div className="font-mono">{new Date(detailLog.createdAt).toLocaleString()}</div>
                            </div>
                            <div>
                                <div className="text-[10px] uppercase tracking-wide text-theme-muted">User</div>
                                <div className="font-mono">{detailLog.username ?? '—'}</div>
                            </div>
                            <div>
                                <div className="text-[10px] uppercase tracking-wide text-theme-muted">Action</div>
                                <Badge variant={ACTION_VARIANTS[detailLog.action] ?? 'info'} size="sm">
                                    {detailLog.action}
                                </Badge>
                            </div>
                            <div>
                                <div className="text-[10px] uppercase tracking-wide text-theme-muted">Version</div>
                                <div className="font-mono">{detailLog.versionNumber != null ? `v${detailLog.versionNumber}` : '—'}</div>
                            </div>
                            <div className="col-span-2">
                                <div className="text-[10px] uppercase tracking-wide text-theme-muted">Audit ID</div>
                                <div className="font-mono text-xs break-all">{detailLog.id}</div>
                            </div>
                        </div>
                        <div>
                            <div className="text-[10px] uppercase tracking-wide text-theme-muted mb-1">Changeset</div>
                            <pre className="text-xs font-mono p-3 bg-obsidian-elevated/50 border border-(--theme-muted)/10 rounded-md overflow-auto max-h-96 whitespace-pre-wrap break-all">
                                {formatChangeset(detailLog.changeset)}
                            </pre>
                        </div>
                    </div>
                )}
            </Dialog>
        </PageContainer>
    );
};
