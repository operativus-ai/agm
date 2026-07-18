import React, { useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { LuActivity, LuCircleDot } from 'react-icons/lu';

import { runsApi } from '../../runs/api/runsApi';
import type { AgentRunResponse } from '../../runs/types/runs';
import { Badge } from '../../../shared/components/ui/Badge';

const POLL_INTERVAL_MS = 30_000;
const PAGE_SIZE = 10;

const formatElapsed = (createdAt: string, now: number): string => {
    const startedAt = new Date(createdAt).getTime();
    const elapsedMs = Math.max(0, now - startedAt);
    const s = Math.floor(elapsedMs / 1000);
    if (s < 60) return `${s}s`;
    const m = Math.floor(s / 60);
    if (m < 60) return `${m}m ${s % 60}s`;
    const h = Math.floor(m / 60);
    return `${h}h ${m % 60}m`;
};

const formatCost = (v: AgentRunResponse['totalCostUsd']): string => {
    if (v === null || v === undefined) return '—';
    const n = typeof v === 'string' ? Number(v) : v;
    return Number.isFinite(n) ? `$${n.toFixed(4)}` : '—';
};

export const RecentActivityWidget: React.FC = () => {
    const navigate = useNavigate();

    const { data, isLoading, error } = useQuery({
        queryKey: ['runs', 'list', { status: 'RUNNING', size: PAGE_SIZE }],
        queryFn: () => runsApi.list({ status: 'RUNNING', size: PAGE_SIZE }),
        refetchInterval: POLL_INTERVAL_MS,
        staleTime: POLL_INTERVAL_MS / 2,
    });

    const rows = data?.content ?? [];
    const now = useMemo(() => Date.now(), [data]);

    return (
        <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl overflow-hidden">
            <div className="px-5 py-3 border-b border-(--theme-muted)/10 flex items-center justify-between">
                <div className="flex items-center gap-2">
                    <LuActivity className="w-4 h-4 text-(--theme-muted)" />
                    <h3 className="text-sm font-medium text-(--theme-foreground)">Recent Activity</h3>
                    {rows.length > 0 && (
                        <Badge variant="info" className="text-xs">{rows.length}</Badge>
                    )}
                </div>
                <span className="text-xs text-(--theme-muted)">Updates every 30 seconds</span>
            </div>

            {error ? (
                <div className="px-5 py-6 text-sm text-(--theme-muted)">
                    Failed to load active runs.
                </div>
            ) : isLoading && rows.length === 0 ? (
                <div className="px-5 py-3 space-y-2">
                    {[1, 2, 3].map(i => (
                        <div key={i} className="h-8 bg-obsidian-elevated/50 rounded-md animate-pulse" />
                    ))}
                </div>
            ) : rows.length === 0 ? (
                <div className="px-5 py-6 text-center text-sm text-(--theme-muted)">
                    No runs in progress.
                </div>
            ) : (
                <table className="w-full text-sm">
                    <thead>
                        <tr className="border-b border-(--theme-muted)/10 text-(--theme-muted) text-xs">
                            <th className="px-5 py-2 text-left font-medium">Agent</th>
                            <th className="px-3 py-2 text-left font-medium">Elapsed</th>
                            <th className="px-3 py-2 text-left font-medium">Strategy</th>
                            <th className="px-3 py-2 text-left font-medium">Model</th>
                            <th className="px-5 py-2 text-right font-medium">Cost</th>
                        </tr>
                    </thead>
                    <tbody>
                        {rows.map(run => (
                            <tr
                                key={run.id}
                                onClick={() => navigate(`/runs/${run.id}`)}
                                className="border-b border-(--theme-muted)/5 hover:bg-(--theme-muted)/5 cursor-pointer transition-colors"
                            >
                                <td className="px-5 py-2.5 font-mono text-xs truncate max-w-[200px]" title={run.agentId}>
                                    <span className="inline-flex items-center gap-2">
                                        <LuCircleDot className="w-3 h-3 text-info animate-pulse" />
                                        {run.agentId}
                                    </span>
                                </td>
                                <td className="px-3 py-2.5 text-xs whitespace-nowrap">{formatElapsed(run.createdAt, now)}</td>
                                <td className="px-3 py-2.5 text-xs text-(--theme-muted)">{run.orchestrationStrategy ?? '—'}</td>
                                <td className="px-3 py-2.5 text-xs text-(--theme-muted)">{run.model ?? '—'}</td>
                                <td className="px-5 py-2.5 text-xs text-right whitespace-nowrap">{formatCost(run.totalCostUsd)}</td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            )}
        </div>
    );
};
