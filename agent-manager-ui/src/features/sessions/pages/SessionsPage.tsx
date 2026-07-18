import React, { useEffect, useMemo, useState } from 'react';
import type { ColumnDef } from '@tanstack/react-table';
import { PageHeader } from '../../../shared/components/ui/PageHeader';
import { Alert } from '../../../shared/components/ui/Alert';
import { Badge } from '../../../shared/components/ui/Badge';
import { Button } from '../../../shared/components/ui/Button';
import { logger } from '../../../utils/logger';
import { DataTable } from '../../../shared/components/ui/DataTable';
import { sessionApi } from '../api/sessionApi';
import type { AgentSession } from '../api/sessionApi';
import { useNavigate } from 'react-router-dom';
import { LuEye, LuTrash2, LuRefreshCw, LuSquareTerminal } from 'react-icons/lu';
import { PageContainer } from '../../../shared/components/ui/PageContainer';

const PAGE_SIZE = 20;

export const SessionsPage: React.FC = () => {
    const [sessions, setSessions] = useState<AgentSession[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [pageIndex, setPageIndex] = useState(0);
    const [totalElements, setTotalElements] = useState(0);
    const navigate = useNavigate();

    useEffect(() => {
        loadSessions();
    }, [pageIndex]);

    const loadSessions = async () => {
        try {
            setLoading(true);
            const data = await sessionApi.listSessions({ page: pageIndex, size: PAGE_SIZE });
            setSessions(data?.content || []);
            setTotalElements(data?.totalElements ?? 0);
            setError(null);
        } catch (err: any) {
            logger.error("Failed to load sessions", err);
            setError(err.message || "Failed to load sessions");
            setSessions([]);
        } finally {
            setLoading(false);
        }
    };

    const handleDelete = async (sessionId: string) => {
        if (!confirm('Are you sure you want to delete this session?')) return;
        try {
            await sessionApi.deleteSession(sessionId);
            setSessions(prev => prev.filter(s => s.id !== sessionId));
            setTotalElements(prev => Math.max(0, prev - 1));
        } catch (err: any) {
            alert(err.message || 'Failed to delete session');
        }
    };

    // ── Column Definitions ──────────────────────────────────────
    const columns = useMemo<ColumnDef<AgentSession, unknown>[]>(() => [
        {
            accessorKey: 'id',
            header: 'Session ID',
            cell: ({ getValue }) => (
                <span className="font-mono text-xs text-(--theme-muted) opacity-70 truncate block max-w-50" title={getValue() as string}>
                    {getValue() as string}
                </span>
            ),
        },
        {
            accessorKey: 'userId',
            header: 'User',
            cell: ({ getValue }) => (
                <span className="text-sm truncate block max-w-[140px]" title={getValue() as string}>
                    {(getValue() as string) || '—'}
                </span>
            ),
        },
        {
            accessorKey: 'agentId',
            header: 'Agent / Team',
            cell: ({ getValue }) => (
                <span className="font-mono text-xs truncate block max-w-45" title={getValue() as string}>
                    {getValue() as string}
                </span>
            ),
        },
        {
            accessorKey: 'status',
            header: 'Status',
            cell: ({ getValue }) => {
                const status = (getValue() as string) || 'UNKNOWN';
                const variant = status === 'ACTIVE' ? 'success' : 'ghost';
                return <Badge variant={variant} className="text-xs">{status}</Badge>;
            },
        },
        {
            accessorKey: 'createdAt',
            header: 'Created',
            cell: ({ getValue }) => (
                <span className="text-xs text-(--theme-muted) whitespace-nowrap">
                    {new Date(getValue() as string).toLocaleString()}
                </span>
            ),
        },
        {
            id: 'actions',
            header: '',
            enableSorting: false,
            cell: ({ row }) => {
                const session = row.original;
                return (
                    <div className="flex items-center justify-end gap-1">
                        <Button
                            size="sm"
                            variant="ghost"
                            title="View Details"
                            className="px-2 text-(--theme-muted) hover:text-primary"
                            onClick={() => navigate(`/sessions/${session.id}`)}
                        >
                            <LuEye className="w-3.5 h-3.5" />
                        </Button>

                        <button
                            type="button"
                            onClick={() => handleDelete(session.id)}
                            aria-label="Delete session"
                            title="Delete"
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
            {/* Header */}
            <PageHeader
                icon={LuSquareTerminal}
                title="Sessions"
                subtitle="View global agent sessions and historical interaction records."
                actions={
                    <Button variant="outline" size="sm" onClick={loadSessions} disabled={loading} className="gap-2">
                        {loading
                            ? <span className="loading loading-spinner loading-sm"></span>
                            : <LuRefreshCw className="w-4 h-4" />
                        }
                        Refresh
                    </Button>
                }
            />

            {error && (
                <Alert severity="error" title="Error">{error}</Alert>
            )}

            {/* Data Table */}
            {loading && sessions.length === 0 ? (
                <div className="space-y-2">
                    {[1, 2, 3, 4, 5].map(i => (
                        <div key={i} className="h-12 bg-obsidian-elevated/50 rounded-md animate-pulse" />
                    ))}
                </div>
            ) : (
                <DataTable
                    columns={columns}
                    data={sessions}
                    manualPagination
                    pageIndex={pageIndex}
                    pageSize={PAGE_SIZE}
                    totalElements={totalElements}
                    onPageChange={setPageIndex}
                    emptyMessage="No sessions found."
                />
            )}
        </PageContainer>
    );
};
