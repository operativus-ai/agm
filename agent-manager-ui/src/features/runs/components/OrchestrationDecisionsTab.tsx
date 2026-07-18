import React, { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { LuChevronDown, LuChevronRight } from 'react-icons/lu';
import { Alert } from '../../../shared/components/ui/Alert';
import { Badge } from '../../../shared/components/ui/Badge';
import { runsApi } from '../api/runsApi';
import type { OrchestrationDecision } from '../types/runs';

const RATIONALE_TRUNC = 80;

const truncate = (s: string | null, max: number): { text: string; truncated: boolean } => {
    if (!s) return { text: '—', truncated: false };
    if (s.length <= max) return { text: s, truncated: false };
    return { text: `${s.slice(0, max).trimEnd()}…`, truncated: true };
};

interface DecisionRowProps {
    decision: OrchestrationDecision;
}

const DecisionRow: React.FC<DecisionRowProps> = ({ decision }) => {
    const [expanded, setExpanded] = useState(false);
    const rationale = truncate(decision.rationale, RATIONALE_TRUNC);
    const hasPayload = decision.decisionPayload && Object.keys(decision.decisionPayload).length > 0;
    const canExpand = hasPayload || rationale.truncated;
    const Chevron = expanded ? LuChevronDown : LuChevronRight;

    return (
        <>
            <tr
                className={`border-b border-(--theme-muted)/5 ${canExpand ? 'cursor-pointer hover:bg-(--theme-muted)/5' : ''} transition-colors`}
                onClick={canExpand ? () => setExpanded(!expanded) : undefined}
            >
                <td className="px-3 py-2 align-top">
                    {canExpand
                        ? <Chevron className="w-3.5 h-3.5 text-(--theme-muted)" />
                        : <span className="inline-block w-3.5" />}
                </td>
                <td className="px-3 py-2 text-xs text-(--theme-muted) whitespace-nowrap align-top">
                    {new Date(decision.createdAt).toLocaleString()}
                </td>
                <td className="px-3 py-2 text-xs align-top">
                    {decision.strategy
                        ? <Badge variant="info" className="text-xs">{decision.strategy}</Badge>
                        : <span className="text-(--theme-muted)">—</span>}
                </td>
                <td className="px-3 py-2 text-xs align-top">
                    {decision.decisionType
                        ? <Badge variant="ghost" className="text-xs">{decision.decisionType}</Badge>
                        : <span className="text-(--theme-muted)">—</span>}
                </td>
                <td className="px-3 py-2 text-xs font-mono align-top">
                    {decision.selectedAgentId
                        ? <Link to={`/agents/${decision.selectedAgentId}`} className="hover:underline" onClick={(e) => e.stopPropagation()}>{decision.selectedAgentId}</Link>
                        : <span className="text-(--theme-muted)">—</span>}
                </td>
                <td className="px-3 py-2 text-xs align-top max-w-[400px]">
                    <span title={decision.rationale ?? undefined}>{rationale.text}</span>
                </td>
            </tr>
            {expanded && (
                <tr className="bg-(--theme-muted)/5">
                    <td />
                    <td colSpan={5} className="px-3 py-3">
                        {rationale.truncated && (
                            <div className="mb-3">
                                <div className="text-[11px] uppercase tracking-wider text-(--theme-muted) mb-1">Rationale</div>
                                <div className="text-xs whitespace-pre-wrap">{decision.rationale}</div>
                            </div>
                        )}
                        {hasPayload && (
                            <div>
                                <div className="text-[11px] uppercase tracking-wider text-(--theme-muted) mb-1">Decision payload</div>
                                <pre className="text-xs font-mono bg-(--theme-card) border border-(--theme-muted)/10 rounded-md p-3 overflow-x-auto">
                                    {JSON.stringify(decision.decisionPayload, null, 2)}
                                </pre>
                            </div>
                        )}
                    </td>
                </tr>
            )}
        </>
    );
};

interface OrchestrationDecisionsTabProps {
    runId: string;
}

export const OrchestrationDecisionsTab: React.FC<OrchestrationDecisionsTabProps> = ({ runId }) => {
    const { data, isLoading, error } = useQuery({
        queryKey: ['runs', 'orchestration-decisions', runId],
        queryFn: () => runsApi.getOrchestrationDecisions(runId),
        staleTime: 60_000,
    });

    if (error) {
        return (
            <Alert severity="error" title="Failed to load orchestration decisions">
                {(error as Error).message}
            </Alert>
        );
    }

    if (isLoading) {
        return (
            <div className="space-y-2">
                {[1, 2, 3].map(i => (
                    <div key={i} className="h-10 bg-obsidian-elevated/50 rounded-md animate-pulse" />
                ))}
            </div>
        );
    }

    const decisions = data ?? [];
    if (decisions.length === 0) {
        return (
            <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl p-8 text-center text-(--theme-muted)">
                No orchestration decisions recorded for this run.
            </div>
        );
    }

    return (
        <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl overflow-hidden">
            <table className="w-full text-sm">
                <thead>
                    <tr className="border-b border-(--theme-muted)/10 text-(--theme-muted) text-xs">
                        <th className="px-3 py-2 w-8"></th>
                        <th className="px-3 py-2 text-left font-medium">Timestamp</th>
                        <th className="px-3 py-2 text-left font-medium">Strategy</th>
                        <th className="px-3 py-2 text-left font-medium">Decision</th>
                        <th className="px-3 py-2 text-left font-medium">Selected Agent</th>
                        <th className="px-3 py-2 text-left font-medium">Rationale</th>
                    </tr>
                </thead>
                <tbody>
                    {decisions.map(d => (
                        <DecisionRow key={d.id} decision={d} />
                    ))}
                </tbody>
            </table>
        </div>
    );
};
