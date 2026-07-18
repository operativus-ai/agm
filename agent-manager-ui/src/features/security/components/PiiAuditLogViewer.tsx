import React, { useState, useEffect } from 'react';
import { piiPolicyApi } from '../api/piiPolicyApi';
import type { PiiAuditLogEntry } from '../types/pii.types';
import { logger } from '../../../utils/logger';
import { DataTable } from '../../../shared/components/ui/DataTable';
import type { ColumnDef } from '@tanstack/react-table';

const PII_AUDIT_COLUMNS: ColumnDef<PiiAuditLogEntry, unknown>[] = [
    {
        accessorKey: 'createdAt',
        header: 'Timestamp',
        cell: ({ getValue }) => (
            <span className="text-xs text-base-content/70 whitespace-nowrap">
                {new Date(getValue() as string).toLocaleString()}
            </span>
        ),
    },
    {
        accessorKey: 'agentId',
        header: 'Agent',
        cell: ({ getValue }) => <span className="font-mono text-xs">{(getValue() as string) || '—'}</span>,
    },
    {
        accessorKey: 'policyName',
        header: 'Policy',
        cell: ({ getValue }) => <span className="font-medium">{getValue() as string}</span>,
    },
    {
        accessorKey: 'scrubStrategy',
        header: 'Strategy',
        cell: ({ getValue }) => {
            const strategy = getValue() as string;
            return (
                <span className={`badge badge-sm ${strategy === 'FPE' ? 'badge-info' : 'badge-warning'}`}>
                    {strategy}
                </span>
            );
        },
    },
    {
        accessorKey: 'occurrences',
        header: 'Occurrences',
        cell: ({ getValue }) => <div className="text-right font-bold">{getValue() as number}</div>,
    },
];

export const PiiAuditLogViewer: React.FC = () => {
    const [entries, setEntries] = useState<PiiAuditLogEntry[]>([]);
    const [loading, setLoading] = useState(true);
    const [filterAgentId, setFilterAgentId] = useState('');

    useEffect(() => {
        loadAuditLog();
    }, []);

    const loadAuditLog = async (agentId?: string) => {
        setLoading(true);
        try {
            const data = await piiPolicyApi.getAuditLog(agentId || undefined);
            setEntries(data);
        } catch (err) {
            logger.error('Failed to load PII audit log', err);
        } finally {
            setLoading(false);
        }
    };

    const handleFilter = (e: React.FormEvent) => {
        e.preventDefault();
        loadAuditLog(filterAgentId.trim() || undefined);
    };

    const totalOccurrences = entries.reduce((sum, e) => sum + e.occurrences, 0);

    return (
        <div className="bg-base-100 border border-base-300 rounded-box overflow-hidden shadow-sm">
            <div className="p-4 border-b border-obsidian-stroke bg-info/10 text-info flex justify-between items-center">
                <div>
                    <h3 className="font-bold text-lg">PII Redaction Audit Log</h3>
                    <p className="text-xs opacity-70">Compliance record of all PII detections and scrub operations</p>
                </div>
                <div className="flex items-center gap-2">
                    {totalOccurrences > 0 && (
                        <div className="badge badge-info gap-1">
                            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20" fill="currentColor" className="w-3 h-3">
                                <path fillRule="evenodd" d="M10 1a4.5 4.5 0 00-4.5 4.5V9H5a2 2 0 00-2 2v6a2 2 0 002 2h10a2 2 0 002-2v-6a2 2 0 00-2-2h-.5V5.5A4.5 4.5 0 0010 1zm3 8V5.5a3 3 0 10-6 0V9h6z" clipRule="evenodd" />
                            </svg>
                            {totalOccurrences} scrubbed
                        </div>
                    )}
                </div>
            </div>

            {/* Agent filter */}
            <div className="p-3 border-b border-obsidian-stroke bg-obsidian-elevated/30">
                <form onSubmit={handleFilter} className="flex gap-2 items-center">
                    <input
                        type="text"
                        placeholder="Filter by Agent ID..."
                        className="input input-bordered input-sm flex-1 max-w-xs"
                        value={filterAgentId}
                        onChange={(e) => setFilterAgentId(e.target.value)}
                    />
                    <button type="submit" className="btn btn-sm btn-ghost">Filter</button>
                    {filterAgentId && (
                        <button
                            type="button"
                            className="btn btn-sm btn-ghost text-base-content/50"
                            onClick={() => { setFilterAgentId(''); loadAuditLog(); }}
                        >
                            Clear
                        </button>
                    )}
                </form>
            </div>

            <div className="p-3">
                {loading ? (
                    <div className="flex justify-center p-8">
                        <span className="loading loading-spinner loading-md text-info"></span>
                    </div>
                ) : (
                    <DataTable
                        columns={PII_AUDIT_COLUMNS}
                        data={entries}
                        enablePagination
                        defaultPageSize={20}
                        emptyMessage="No PII scrub events recorded yet. Events appear here when agents process data containing sensitive patterns."
                    />
                )}
            </div>
        </div>
    );
};
