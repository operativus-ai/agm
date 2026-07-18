import React, { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { LuChevronLeft, LuChevronRight, LuRefreshCw } from 'react-icons/lu';
import { Alert } from '../../../shared/components/ui/Alert';
import { Button } from '../../../shared/components/ui/Button';
import { agentReflectionsApi, type AgentReflection } from '../api/agentReflectionsApi';

interface AgentReflectionsTabProps {
    agentId: string;
}

const PAGE_SIZE = 20;

const formatTimestamp = (iso: string): string => {
    const d = new Date(iso);
    return Number.isNaN(d.getTime()) ? '—' : d.toLocaleString();
};

export const AgentReflectionsTab: React.FC<AgentReflectionsTabProps> = ({ agentId }) => {
    const [page, setPage] = useState(0);

    const { data, isLoading, isFetching, error, refetch } = useQuery({
        queryKey: ['agents', 'reflections', agentId, page, PAGE_SIZE],
        queryFn: () => agentReflectionsApi.list(agentId, page, PAGE_SIZE),
        staleTime: 30_000,
    });

    if (error) {
        return (
            <Alert severity="error" title="Failed to load reflections">
                {(error as Error).message}
            </Alert>
        );
    }

    return (
        <div className="space-y-3">
            <div className="flex items-center justify-between text-xs">
                <span className="text-(--theme-muted)">
                    {data ? `${data.page.totalElements.toLocaleString()} reflections` : 'Loading…'}
                </span>
                <Button
                    variant="outline"
                    size="sm"
                    onClick={() => refetch()}
                    disabled={isFetching}
                    className="gap-2"
                >
                    {isFetching ? <span className="loading loading-spinner loading-sm" /> : <LuRefreshCw className="w-4 h-4" />}
                    Refresh
                </Button>
            </div>

            {isLoading && !data ? (
                <div className="space-y-2">
                    {[1, 2, 3].map(i => (
                        <div key={i} className="h-20 bg-obsidian-elevated/50 rounded-xl animate-pulse" />
                    ))}
                </div>
            ) : data && data.content.length === 0 ? (
                <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl p-8 text-center text-(--theme-muted)">
                    No reflections recorded for this agent yet.
                </div>
            ) : data ? (
                <ul className="space-y-2">
                    {data.content.map((r: AgentReflection) => (
                        <li
                            key={r.id}
                            className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl px-4 py-3"
                        >
                            <div className="flex items-baseline justify-between gap-3 text-xs text-(--theme-muted) mb-2">
                                <span>{formatTimestamp(r.createdAt)}</span>
                                {r.sourceRunId && (
                                    <Link
                                        to={`/runs/${r.sourceRunId}`}
                                        className="font-mono hover:underline"
                                        title={r.sourceRunId}
                                    >
                                        run: {r.sourceRunId.slice(0, 12)}…
                                    </Link>
                                )}
                            </div>
                            <pre className="whitespace-pre-wrap text-sm text-(--theme-foreground) leading-relaxed font-sans">
                                {r.content || <span className="text-(--theme-muted) italic">(empty reflection)</span>}
                            </pre>
                        </li>
                    ))}
                </ul>
            ) : null}

            {data && data.page.totalPages > 1 && (
                <div className="flex items-center justify-between text-xs text-(--theme-muted)">
                    <span>Page {data.page.number + 1} of {data.page.totalPages}</span>
                    <div className="flex items-center gap-2">
                        <Button
                            variant="ghost"
                            size="sm"
                            onClick={() => setPage(p => Math.max(0, p - 1))}
                            disabled={data.page.number === 0 || isFetching}
                            className="gap-1"
                        >
                            <LuChevronLeft className="w-4 h-4" />
                            Prev
                        </Button>
                        <Button
                            variant="ghost"
                            size="sm"
                            onClick={() => setPage(p => p + 1)}
                            disabled={data.page.number + 1 >= data.page.totalPages || isFetching}
                            className="gap-1"
                        >
                            Next
                            <LuChevronRight className="w-4 h-4" />
                        </Button>
                    </div>
                </div>
            )}
        </div>
    );
};
