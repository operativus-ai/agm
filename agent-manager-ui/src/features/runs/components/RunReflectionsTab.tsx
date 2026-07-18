import React from 'react';
import { useQuery } from '@tanstack/react-query';
import { LuRefreshCw } from 'react-icons/lu';
import { Alert } from '../../../shared/components/ui/Alert';
import { Button } from '../../../shared/components/ui/Button';
import { runsApi } from '../api/runsApi';
import type { RunReflection } from '../types/runs';

interface RunReflectionsTabProps {
    runId: string;
}

const formatTimestamp = (iso: string): string => {
    const d = new Date(iso);
    return Number.isNaN(d.getTime()) ? '—' : d.toLocaleString();
};

/**
 * §3 P3 ReflectionLog viewer (run scope). Lists the reflections produced during
 * a single run in step-index order. Reflections only appear for agents wired
 * with self-critique advisors (e.g. StructuredOutputRetryAdvisor); a run with
 * zero reflections is normal and shown as an empty-state, not an error.
 *
 * No pagination — reflection chains are bounded (typically 1–10 per run); a
 * runaway count is itself diagnostic and worth seeing in full.
 */
export const RunReflectionsTab: React.FC<RunReflectionsTabProps> = ({ runId }) => {
    const { data, isLoading, isFetching, error, refetch } = useQuery({
        queryKey: ['runs', 'reflections', runId],
        queryFn: () => runsApi.getReflections(runId),
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
                    {data ? `${data.length.toLocaleString()} reflections` : 'Loading…'}
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
            ) : data && data.length === 0 ? (
                <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl p-8 text-center text-(--theme-muted)">
                    No reflections recorded for this run. Reflections only appear for agents wired with self-critique advisors.
                </div>
            ) : data ? (
                <ul className="space-y-2">
                    {data.map((r: RunReflection, idx: number) => (
                        <li
                            key={r.id}
                            className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl px-4 py-3"
                        >
                            <div className="flex items-baseline justify-between gap-3 text-xs text-(--theme-muted) mb-2">
                                <span>
                                    Step {idx + 1}
                                    {r.agentId && (
                                        <>
                                            {' · '}
                                            <span className="font-mono" title={r.agentId}>
                                                agent: {r.agentId.slice(0, 12)}…
                                            </span>
                                        </>
                                    )}
                                </span>
                                <span>{formatTimestamp(r.createdAt)}</span>
                            </div>
                            <pre className="whitespace-pre-wrap text-sm text-(--theme-foreground) leading-relaxed font-sans">
                                {r.content || <span className="text-(--theme-muted) italic">(empty reflection)</span>}
                            </pre>
                        </li>
                    ))}
                </ul>
            ) : null}
        </div>
    );
};
