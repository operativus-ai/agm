import React, { useState, useEffect, useRef, useMemo } from 'react';
import { PageHeader } from '../../../shared/components/ui/PageHeader';
import { Button } from '../../../shared/components/ui/Button';
import { DataTable } from '../../../shared/components/ui/DataTable';
import { mcpApi } from '../api/mcpApi';
import type { McpJsonRpcRequest, McpTool } from '../api/mcpApi';
import type { ColumnDef } from '@tanstack/react-table';
import {
    LuPlug, LuWifi, LuWifiOff, LuTrash2, LuCode,
} from 'react-icons/lu';
import { PageContainer } from '../../../shared/components/ui/PageContainer';
import { McpOutboundServersPanel } from '../components/McpOutboundServersPanel';

export const McpAdminPage: React.FC = () => {
    const [status, setStatus] = useState<'DISCONNECTED' | 'CONNECTING' | 'CONNECTED'>('DISCONNECTED');
    const [messages, setMessages] = useState<{ id: string, type: 'IN' | 'OUT', content: string, timestamp: Date }[]>([]);
    const [endpointUrl, setEndpointUrl] = useState<string | null>(null);
    const [tools, setTools] = useState<McpTool[]>([]);

    const abortControllerRef = useRef<AbortController | null>(null);

    const logMessage = (type: 'IN' | 'OUT', data: any) => {
        setMessages(prev => [{
            id: crypto.randomUUID(),
            type,
            content: typeof data === 'string' ? data : JSON.stringify(data, null, 2),
            timestamp: new Date()
        }, ...prev].slice(0, 50));
    };

    const connectMcp = () => {
        if (abortControllerRef.current) {
            abortControllerRef.current.abort();
        }

        setStatus('CONNECTING');

        const ctrl = mcpApi.connectSse({
            onEndpoint: (url) => {
                setEndpointUrl(url);
                logMessage('IN', `Received Endpoint: ${url}`);
                fetchTools(url);
            },
            onOpen: () => {
                setStatus('CONNECTED');
                logMessage('IN', 'SSE Connection Established');
            },
            onError: () => {
                setStatus('DISCONNECTED');
                logMessage('IN', 'SSE Connection Error/Closed');
            },
            onClose: () => {
                setStatus('DISCONNECTED');
                logMessage('IN', 'SSE Connection Closed');
            },
        });

        abortControllerRef.current = ctrl;
    };

    const fetchTools = async (url: string) => {
        try {
            const msg: McpJsonRpcRequest = {
                jsonrpc: '2.0',
                id: crypto.randomUUID(),
                method: 'tools/list',
            };
            logMessage('OUT', msg);

            const response = await mcpApi.sendMessage(url, msg);
            logMessage('IN', response);

            if (response.result?.tools) {
                setTools(response.result.tools);
            }
        } catch (err: any) {
            logMessage('IN', `Error fetching tools: ${err.message}`);
        }
    };

    const disconnectMcp = () => {
        if (abortControllerRef.current) {
            abortControllerRef.current.abort();
            abortControllerRef.current = null;
        }
        setStatus('DISCONNECTED');
        setEndpointUrl(null);
        setTools([]);
        logMessage('IN', 'Disconnected by user');
    };

    useEffect(() => {
        return () => {
            if (abortControllerRef.current) {
                abortControllerRef.current.abort();
            }
        };
    }, []);

    // ── Tool Column Definitions ──────────────────────────────────
    const toolColumns = useMemo<ColumnDef<McpTool, unknown>[]>(() => [
        {
            accessorKey: 'name',
            header: 'Tool Name',
            cell: ({ getValue }) => (
                <span className="font-mono text-sm text-primary font-bold">{getValue() as string}</span>
            ),
        },
        {
            accessorKey: 'description',
            header: 'Description',
            cell: ({ getValue }) => (
                <span className="text-xs text-(--theme-muted) leading-relaxed line-clamp-2 max-w-[400px] block">
                    {getValue() as string}
                </span>
            ),
        },
        {
            id: 'schema',
            header: 'Schema',
            enableSorting: false,
            cell: ({ row }) => {
                const schema = row.original.inputSchema;
                const paramCount = schema ? Object.keys(schema.properties || {}).length : 0;
                return (
                    <span className="font-mono bg-obsidian-elevated px-2 py-0.5 rounded text-xs">
                        {paramCount} params
                    </span>
                );
            },
        },
    ], []);

    const statusIndicator = status === 'CONNECTED' ? 'bg-active-green animate-pulse' : status === 'CONNECTING' ? 'bg-warn-amber animate-pulse' : 'bg-error-red';

    return (
        <PageContainer variant="dashboard">
            {/* Header */}
            <PageHeader
                icon={LuPlug}
                title="MCP Server Admin"
                subtitle="Monitor inbound MCP server endpoints (AgentManager-as-server) and the outbound client pool (AgentManager-as-client)."
            />

            <McpOutboundServersPanel />

            <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">

                {/* Connection Panel */}
                <div className="space-y-6 lg:col-span-1">
                    <div className="bg-(--theme-card) rounded-xl border border-(--theme-muted)/10 p-5 space-y-4 shadow-sm">
                        <h3 className="text-sm font-bold uppercase tracking-wider text-(--theme-muted)">Connection</h3>

                        <div className="flex items-center gap-3">
                            <div className={`w-3 h-3 rounded-full ${statusIndicator}`}></div>
                            <span className="font-mono text-sm font-bold">{status}</span>
                        </div>

                        {status === 'DISCONNECTED' ? (
                            <Button size="sm" className="w-full gap-1.5" onClick={connectMcp}>
                                <LuWifi className="w-3.5 h-3.5" /> Connect to SSE Stream
                            </Button>
                        ) : (
                            <Button variant="outline" size="sm" className="w-full gap-1.5 text-error border-error/30 hover:bg-error/10" onClick={disconnectMcp}>
                                <LuWifiOff className="w-3.5 h-3.5" /> Disconnect
                            </Button>
                        )}

                        {endpointUrl && (
                            <div className="pt-3 border-t border-(--theme-muted)/10">
                                <div className="text-[10px] uppercase font-bold text-(--theme-muted) mb-1">Active Endpoint</div>
                                <div className="text-xs font-mono break-all bg-obsidian-elevated p-2 rounded">{endpointUrl}</div>
                            </div>
                        )}
                    </div>

                    {/* Exposed Tools DataTable */}
                    <div className="bg-(--theme-card) rounded-xl border border-(--theme-muted)/10 p-5 space-y-3 shadow-sm">
                        <h3 className="text-sm font-bold uppercase tracking-wider text-(--theme-muted) flex items-center gap-2">
                            <LuCode className="w-3.5 h-3.5" /> Exposed Tools
                        </h3>
                        {tools.length === 0 ? (
                            <div className="text-center py-4 text-(--theme-muted) text-sm italic">
                                {status === 'CONNECTED' ? 'No tools discovered.' : 'Connect to view tools.'}
                            </div>
                        ) : (
                            <DataTable
                                columns={toolColumns}
                                data={tools}
                                enablePagination
                                defaultPageSize={25}
                                compact
                                emptyMessage="No tools discovered."
                            />
                        )}
                    </div>
                </div>

                {/* Log Viewer */}
                <div className="bg-(--theme-card) rounded-xl border border-(--theme-muted)/10 lg:col-span-2 flex flex-col max-h-[600px] shadow-sm overflow-hidden">
                    <div className="px-5 py-3 border-b border-(--theme-muted)/10 flex justify-between items-center">
                        <h3 className="text-sm font-bold uppercase tracking-wider text-(--theme-muted)">JSON-RPC Traffic Log</h3>
                        <Button variant="ghost" size="sm" className="gap-1 text-(--theme-muted)" onClick={() => setMessages([])}>
                            <LuTrash2 className="w-3 h-3" /> Clear
                        </Button>
                    </div>

                    <div className="flex-1 overflow-y-auto p-4 space-y-3 bg-obsidian-elevated/50 text-(--theme-foreground) font-mono text-xs">
                        {messages.length === 0 ? (
                            <div className="text-center py-8 text-(--theme-muted) italic">No traffic logs…</div>
                        ) : (
                            messages.map(msg => (
                                <div key={msg.id} className="border-b border-(--theme-muted)/10 pb-3">
                                    <div className="flex justify-between items-center mb-1 text-[10px] text-(--theme-muted)">
                                        <span className={`font-bold ${msg.type === 'IN' ? 'text-active-green' : 'text-agent-blue'}`}>
                                            {msg.type === 'IN' ? '↓ RECV' : '↑ SEND'}
                                        </span>
                                        <span>{msg.timestamp.toLocaleTimeString()}</span>
                                    </div>
                                    <pre className="whitespace-pre-wrap break-words">{msg.content}</pre>
                                </div>
                            ))
                        )}
                    </div>
                </div>

            </div>
        </PageContainer>
    );
};
