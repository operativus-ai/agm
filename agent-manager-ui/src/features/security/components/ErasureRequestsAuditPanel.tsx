import React, { useState } from 'react';
import { LuRefreshCw, LuSearch } from 'react-icons/lu';
import type { ColumnDef } from '@tanstack/react-table';
import { Alert } from '../../../shared/components/ui/Alert';
import { Badge } from '../../../shared/components/ui/Badge';
import { Button } from '../../../shared/components/ui/Button';
import { DataTable } from '../../../shared/components/ui/DataTable';
import {
    complianceApi,
    type ErasureRequest,
    type ErasureRequestStatus,
} from '../api/complianceApi';

const statusVariant = (s: ErasureRequestStatus): 'success' | 'error' | 'warning' | 'info' => {
    if (s === 'COMPLETED') return 'success';
    if (s === 'FAILED') return 'error';
    if (s === 'IN_PROGRESS') return 'info';
    return 'warning'; // PENDING, PARTIAL
};

const formatTimestamp = (iso: string | null): string => {
    if (!iso) return '—';
    const d = new Date(iso);
    return Number.isNaN(d.getTime()) ? '—' : d.toLocaleString();
};

const ERASURE_COLUMNS: ColumnDef<ErasureRequest, unknown>[] = [
    {
        accessorKey: 'status',
        header: 'Status',
        cell: ({ getValue }) => <Badge variant={statusVariant(getValue() as ErasureRequestStatus)} className="text-xs">{getValue() as string}</Badge>,
    },
    {
        accessorKey: 'requestedAt',
        header: 'Requested',
        cell: ({ getValue }) => <span className="text-xs whitespace-nowrap">{formatTimestamp(getValue() as string | null)}</span>,
    },
    {
        accessorKey: 'requestedBy',
        header: 'By',
        cell: ({ getValue }) => <span className="text-xs font-mono" title={getValue() as string}>{getValue() as string}</span>,
    },
    {
        accessorKey: 'startedAt',
        header: 'Started',
        cell: ({ getValue }) => <span className="text-xs text-(--theme-muted) whitespace-nowrap">{formatTimestamp(getValue() as string | null)}</span>,
    },
    {
        accessorKey: 'completedAt',
        header: 'Completed',
        cell: ({ getValue }) => <span className="text-xs text-(--theme-muted) whitespace-nowrap">{formatTimestamp(getValue() as string | null)}</span>,
    },
];

export const ErasureRequestsAuditPanel: React.FC = () => {
    const [userId, setUserId] = useState('');
    const [requests, setRequests] = useState<ErasureRequest[] | null>(null);
    const [selected, setSelected] = useState<ErasureRequest | null>(null);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const lookupHistory = async () => {
        const trimmed = userId.trim();
        if (!trimmed) return;
        setLoading(true);
        setError(null);
        setSelected(null);
        try {
            const rows = await complianceApi.listErasureRequests(trimmed);
            setRequests(rows);
        } catch (err) {
            setError((err as Error).message || 'Failed to load erasure requests.');
            setRequests([]);
        } finally {
            setLoading(false);
        }
    };

    return (
        <div className="space-y-4">
            <p className="text-sm text-(--theme-muted)">
                Audit trail of GDPR erasure requests for a specific user. Each entry shows the request's lifecycle —
                submitted, in-progress, completed, or failed — including the responsible operator and a per-request summary.
            </p>

            <div className="flex items-end gap-3">
                <div className="flex-1 max-w-md space-y-1">
                    <label className="text-xs font-bold uppercase tracking-wide text-(--theme-muted)">User ID</label>
                    <input
                        type="text"
                        placeholder="e.g. user-abc123"
                        className="input input-bordered input-sm w-full font-mono"
                        value={userId}
                        onChange={e => setUserId(e.target.value)}
                        onKeyDown={e => { if (e.key === 'Enter') lookupHistory(); }}
                    />
                </div>
                <Button
                    variant="outline"
                    size="sm"
                    className="gap-1.5"
                    disabled={!userId.trim() || loading}
                    onClick={lookupHistory}
                >
                    {loading ? <span className="loading loading-spinner loading-xs" /> : <LuSearch className="w-4 h-4" />}
                    Look up
                </Button>
            </div>

            {error && <Alert severity="error">{error}</Alert>}

            {requests && requests.length === 0 && !loading && !error && (
                <div className="text-(--theme-muted) text-sm py-4">
                    No erasure requests on record for user <span className="font-mono">{userId.trim()}</span>.
                </div>
            )}

            {requests && requests.length > 0 && (
                <div className="space-y-2">
                    <div className="flex items-center justify-between text-xs">
                        <span className="text-(--theme-muted)">
                            {requests.length} request{requests.length === 1 ? '' : 's'} for <span className="font-mono">{userId.trim()}</span>
                        </span>
                        <Button variant="ghost" size="sm" className="gap-1.5" onClick={lookupHistory} disabled={loading}>
                            <LuRefreshCw className="w-3.5 h-3.5" />
                            Refresh
                        </Button>
                    </div>
                    <DataTable
                        columns={ERASURE_COLUMNS}
                        data={requests}
                        onRowClick={setSelected}
                        enablePagination
                        defaultPageSize={20}
                    />
                </div>
            )}

            {selected && (
                <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl px-5 py-4 space-y-3">
                    <div className="flex items-center justify-between">
                        <div className="text-xs font-medium text-(--theme-foreground)">
                            Request <span className="font-mono">{selected.id.slice(0, 12)}…</span>
                        </div>
                        <button
                            type="button"
                            onClick={() => setSelected(null)}
                            className="text-xs text-(--theme-muted) hover:text-(--theme-foreground)"
                        >
                            Close
                        </button>
                    </div>

                    {selected.errorMessage && (
                        <Alert severity="error" title="Error">{selected.errorMessage}</Alert>
                    )}

                    {selected.summary && Object.keys(selected.summary).length > 0 ? (
                        <div>
                            <div className="text-[11px] uppercase tracking-wide text-(--theme-muted) mb-1">Summary</div>
                            <pre className="text-xs font-mono bg-obsidian-elevated/40 rounded-md p-3 overflow-x-auto whitespace-pre-wrap">
                                {JSON.stringify(selected.summary, null, 2)}
                            </pre>
                        </div>
                    ) : (
                        <div className="text-xs text-(--theme-muted)">No summary recorded.</div>
                    )}
                </div>
            )}
        </div>
    );
};
