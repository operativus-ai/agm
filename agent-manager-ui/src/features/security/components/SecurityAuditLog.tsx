import React from 'react';
import { useQuery } from '@tanstack/react-query';
import { securityApi } from '../api/securityApi';
import type { ThreatEvent } from '../api/securityApi';
import { GatewayLogStatus } from '../../../shared/types/enums';
import { DataTable } from '../../../shared/components/ui/DataTable';
import type { ColumnDef } from '@tanstack/react-table';

const THREAT_COLUMNS: ColumnDef<ThreatEvent, unknown>[] = [
    {
        accessorKey: 'timestamp',
        header: 'Time',
        cell: ({ getValue }) => {
            const ts = getValue() as string | Date;
            return (
                <span className="text-xs text-base-content/70">
                    {typeof ts === 'string' ? new Date(ts).toLocaleTimeString() : ts.toLocaleTimeString()}
                </span>
            );
        },
    },
    {
        accessorKey: 'type',
        header: 'Threat Vector',
        cell: ({ row }) => (
            <div>
                <div className="font-medium">{row.original.type}</div>
                <div className="text-[10px] opacity-60">Target: {row.original.target}</div>
            </div>
        ),
    },
    {
        accessorKey: 'threatLevel',
        header: 'Severity',
        cell: ({ getValue }) => {
            const level = getValue() as string;
            if (level === 'CRITICAL') return <span className="text-xs text-red-500 font-bold">{level}</span>;
            if (level === 'HIGH') return <span className="text-xs text-orange-500 font-bold">{level}</span>;
            if (level === 'LOW') return <span className="text-xs text-yellow-500 font-bold">{level}</span>;
            return <span className="text-xs font-bold">{level}</span>;
        },
    },
    {
        accessorKey: 'agentId',
        header: 'Agent ID',
        cell: ({ getValue }) => <span className="font-mono text-xs">{getValue() as string}</span>,
    },
    {
        accessorKey: 'status',
        header: 'Status',
        cell: ({ getValue }) =>
            getValue() === GatewayLogStatus.BLOCKED ? (
                <span className="badge badge-error badge-sm">BLOCKED</span>
            ) : (
                <span className="badge badge-warning badge-sm">FLAGGED</span>
            ),
    },
];

export const SecurityAuditLog: React.FC = () => {
    const { data: simulatedEvents = [] } = useQuery({
        queryKey: ['threatEvents'],
        queryFn: () => securityApi.getThreatEvents()
    });

    return (
        <div className="bg-base-100 border border-base-300 rounded-box overflow-hidden shadow-sm h-full">
            <div className="p-4 border-b border-base-200 bg-error/10 text-error flex justify-between items-center">
                <div>
                    <h3 className="font-bold text-lg">Dynamic Security Audit Log</h3>
                    <p className="text-xs opacity-70">Prompt Injection & Tool Hijack attempts blocked by the Security Scanner</p>
                </div>
                <div className="badge badge-error gap-1 animate-pulse"><span className="w-2 h-2 rounded-full bg-white"></span> Active Shield</div>
            </div>
            
            <div className="p-3">
                <DataTable
                    columns={THREAT_COLUMNS}
                    data={simulatedEvents}
                    enablePagination
                    defaultPageSize={10}
                    emptyMessage="No threat events recorded."
                />
            </div>
        </div>
    );
};
