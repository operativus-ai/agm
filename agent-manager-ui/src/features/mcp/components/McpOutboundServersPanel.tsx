import React, { useCallback, useEffect, useMemo, useState } from 'react';
import type { ColumnDef } from '@tanstack/react-table';
import { Badge } from '../../../shared/components/ui/Badge';
import { Button } from '../../../shared/components/ui/Button';
import { DataTable } from '../../../shared/components/ui/DataTable';
import { Alert } from '../../../shared/components/ui/Alert';
import { mcpLifecycleApi } from '../api/mcpLifecycleApi';
import type { McpPoolStatus, McpServerSummary, McpReconnectResponse } from '../api/mcpLifecycleApi';
import { LuRefreshCw, LuServer, LuCircleCheck, LuCircleX, LuPlug } from 'react-icons/lu';

const REFRESH_INTERVAL_MS = 15_000;

interface RowFeedback {
    id: string;
    status: McpReconnectResponse['status'];
    durationMs: number;
    timestamp: number;
}

export const McpOutboundServersPanel: React.FC = () => {
    const [status, setStatus] = useState<McpPoolStatus | null>(null);
    const [servers, setServers] = useState<McpServerSummary[]>([]);
    const [loading, setLoading] = useState(true);
    const [refreshing, setRefreshing] = useState(false);
    const [reconnectingIds, setReconnectingIds] = useState<Set<string>>(new Set());
    const [error, setError] = useState<string | null>(null);
    const [feedback, setFeedback] = useState<RowFeedback | null>(null);

    const loadAll = useCallback(async (silent = false) => {
        if (!silent) setRefreshing(true);
        try {
            const [s, list] = await Promise.all([
                mcpLifecycleApi.getStatus(),
                mcpLifecycleApi.listServers(),
            ]);
            setStatus(s);
            setServers(list);
            setError(null);
        } catch (err: any) {
            setError(err?.message || 'Failed to load MCP pool state.');
        } finally {
            if (!silent) setRefreshing(false);
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        loadAll();
        const handle = setInterval(() => loadAll(true), REFRESH_INTERVAL_MS);
        return () => clearInterval(handle);
    }, [loadAll]);

    const handleReconnect = async (id: string) => {
        setReconnectingIds(prev => new Set(prev).add(id));
        setFeedback(null);
        try {
            const res = await mcpLifecycleApi.reconnect(id);
            setFeedback({ id, status: res.status, durationMs: res.durationMs, timestamp: Date.now() });
            await loadAll(true);
        } catch (err: any) {
            setFeedback({ id, status: 'FAILED', durationMs: 0, timestamp: Date.now() });
            setError(err?.message || `Reconnect failed for ${id}.`);
        } finally {
            setReconnectingIds(prev => {
                const next = new Set(prev);
                next.delete(id);
                return next;
            });
        }
    };

    const columns = useMemo<ColumnDef<McpServerSummary, unknown>[]>(() => [
        {
            accessorKey: 'name',
            header: 'Server',
            cell: ({ row }) => (
                <div className="flex items-center gap-2 min-w-0">
                    <LuServer className="w-4 h-4 shrink-0 text-(--theme-muted)" />
                    <div className="min-w-0">
                        <div className="font-medium text-sm truncate max-w-45" title={row.original.name}>
                            {row.original.name}
                        </div>
                        <div className="font-mono text-[10px] text-(--theme-muted) truncate max-w-50" title={row.original.id}>
                            {row.original.id}
                        </div>
                    </div>
                </div>
            ),
        },
        {
            accessorKey: 'url',
            header: 'URL',
            cell: ({ getValue }) => (
                <code className="text-xs font-mono text-(--theme-muted) bg-obsidian-elevated px-2 py-0.5 rounded truncate block max-w-75" title={getValue() as string}>
                    {getValue() as string}
                </code>
            ),
        },
        {
            accessorKey: 'active',
            header: 'Enabled',
            cell: ({ getValue }) => {
                const active = getValue() as boolean;
                return active
                    ? <Badge variant="success" outline className="text-xs">Active</Badge>
                    : <Badge variant="neutral" outline className="text-xs">Disabled</Badge>;
            },
        },
        {
            accessorKey: 'connectionStatus',
            header: 'Connection',
            cell: ({ row }) => {
                const connected = row.original.connectionStatus === 'CONNECTED';
                return (
                    <div className="flex items-center gap-1.5">
                        {connected
                            ? <LuCircleCheck className="w-3.5 h-3.5 text-active-green" />
                            : <LuCircleX className="w-3.5 h-3.5 text-error" />
                        }
                        <span className={`text-xs font-mono ${connected ? 'text-active-green' : 'text-error'}`}>
                            {row.original.connectionStatus}
                        </span>
                    </div>
                );
            },
        },
        {
            accessorKey: 'toolCount',
            header: 'Tools',
            cell: ({ getValue }) => (
                <span className="font-mono text-xs">{getValue() as number}</span>
            ),
        },
        {
            id: 'actions',
            header: '',
            enableSorting: false,
            cell: ({ row }) => {
                const id = row.original.id;
                const reconnecting = reconnectingIds.has(id);
                const lastFeedback = feedback?.id === id ? feedback : null;
                return (
                    <div className="flex items-center justify-end gap-2">
                        {lastFeedback && (
                            <span
                                className={`text-[10px] font-mono ${
                                    lastFeedback.status === 'RECONNECTED' ? 'text-active-green'
                                    : lastFeedback.status === 'TIMEOUT' ? 'text-warn-amber'
                                    : 'text-error'
                                }`}
                                title={`${lastFeedback.status} in ${lastFeedback.durationMs}ms`}
                            >
                                {lastFeedback.status}
                            </span>
                        )}
                        <Button
                            size="sm"
                            variant="ghost"
                            className="gap-1 text-(--theme-muted) hover:text-primary"
                            disabled={reconnecting || !row.original.active}
                            onClick={() => handleReconnect(id)}
                            title={row.original.active ? 'Force reconnect' : 'Server is disabled'}
                        >
                            {reconnecting
                                ? <span className="loading loading-spinner loading-xs" />
                                : <LuPlug className="w-3.5 h-3.5" />
                            }
                            Reconnect
                        </Button>
                    </div>
                );
            },
        },
    ], [reconnectingIds, feedback]);

    return (
        <div className="bg-(--theme-card) rounded-xl border border-(--theme-muted)/10 p-5 space-y-4 shadow-sm">
            <div className="flex items-center justify-between gap-3 flex-wrap">
                <div>
                    <h3 className="text-sm font-bold uppercase tracking-wider text-(--theme-muted) flex items-center gap-2">
                        <LuServer className="w-3.5 h-3.5" /> Outbound MCP Client Pool
                    </h3>
                    <p className="text-xs text-(--theme-muted) mt-1">
                        State of AgentManager's outbound connections to remote MCP servers. Refreshes every 15s.
                    </p>
                </div>
                <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => loadAll()}
                    disabled={refreshing}
                    className="gap-1.5"
                    title="Refresh now"
                >
                    {refreshing
                        ? <span className="loading loading-spinner loading-xs" />
                        : <LuRefreshCw className="w-3.5 h-3.5" />
                    }
                    Refresh
                </Button>
            </div>

            {error && (
                <Alert severity="error" description={error} dismissible onClose={() => setError(null)} />
            )}

            {/* Aggregate counters */}
            {status && (
                <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
                    <PoolStat label="Configured" value={status.configured} />
                    <PoolStat label="Active" value={status.active} accent={status.active < status.configured ? 'warn' : 'ok'} />
                    <PoolStat label="Connected" value={status.connected} accent={status.connected < status.active ? 'warn' : 'ok'} />
                    <PoolStat label="Exposed Tools" value={status.totalTools} />
                </div>
            )}

            {/* Per-server table */}
            {loading ? (
                <div className="space-y-2">
                    {[1, 2, 3].map(i => (
                        <div key={i} className="h-12 bg-obsidian-elevated/50 rounded-md animate-pulse" />
                    ))}
                </div>
            ) : (
                <DataTable
                    columns={columns}
                    data={servers}
                    enablePagination
                    defaultPageSize={25}
                    compact
                    emptyMessage="No outbound MCP servers configured. Register one via Extensions."
                />
            )}
        </div>
    );
};

const PoolStat: React.FC<{ label: string; value: number; accent?: 'ok' | 'warn' }> = ({ label, value, accent }) => {
    const color =
        accent === 'warn' ? 'text-warn-amber'
        : accent === 'ok' ? 'text-active-green'
        : 'text-(--theme-foreground)';
    return (
        <div className="bg-obsidian-elevated/50 rounded-md px-3 py-2">
            <div className="text-[10px] uppercase font-bold text-(--theme-muted) tracking-wider">{label}</div>
            <div className={`text-2xl font-mono font-bold ${color}`}>{value}</div>
        </div>
    );
};
