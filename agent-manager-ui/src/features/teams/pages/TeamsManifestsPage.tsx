import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { LuArrowLeft, LuChevronDown, LuChevronRight, LuRefreshCw, LuUsers } from 'react-icons/lu';
import { PageContainer } from '../../../shared/components/ui/PageContainer';
import { PageHeader } from '../../../shared/components/ui/PageHeader';
import { Alert } from '../../../shared/components/ui/Alert';
import { Badge } from '../../../shared/components/ui/Badge';
import { Button } from '../../../shared/components/ui/Button';
import { orchestrationApi } from '../../../shared/api/orchestrationApi';
import type { TeamManifest } from '../../../shared/types/orchestration';

const formatUsd = (n: number): string =>
    Number.isFinite(n) ? `$${n.toFixed(2)}` : '—';

const agentCount = (m: TeamManifest): number => Object.keys(m.agents ?? {}).length;

export const TeamsManifestsPage: React.FC = () => {
    const [manifests, setManifests] = useState<TeamManifest[] | null>(null);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [expanded, setExpanded] = useState<Set<string>>(new Set());

    const load = async () => {
        setLoading(true);
        setError(null);
        try {
            const data = await orchestrationApi.listTeamManifests();
            setManifests(data ?? []);
        } catch (err) {
            setError((err as Error).message || 'Failed to load manifests.');
            setManifests([]);
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        load();
    }, []);

    const toggle = (teamId: string) => {
        setExpanded(prev => {
            const next = new Set(prev);
            if (next.has(teamId)) next.delete(teamId);
            else next.add(teamId);
            return next;
        });
    };

    return (
        <PageContainer variant="dashboard">
            <PageHeader
                icon={LuUsers}
                title="Team Manifests"
                subtitle="Cross-team catalog of human-lead, spend caps, capabilities, and agent role assignments."
                actions={
                    <>
                        <Link to="/teams">
                            <Button variant="ghost" size="sm" className="gap-2">
                                <LuArrowLeft className="w-4 h-4" />
                                Back to teams
                            </Button>
                        </Link>
                        <Button
                            variant="outline"
                            size="sm"
                            onClick={load}
                            disabled={loading}
                            className="gap-2"
                        >
                            {loading
                                ? <span className="loading loading-spinner loading-sm" />
                                : <LuRefreshCw className="w-4 h-4" />}
                            Refresh
                        </Button>
                    </>
                }
            />

            <div className="text-xs text-(--theme-muted) -mt-2">
                {manifests ? `${manifests.length} manifest${manifests.length === 1 ? '' : 's'}` : 'Loading…'}
            </div>

            {error && <Alert severity="error">{error}</Alert>}

            <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl overflow-hidden">
                {loading && !manifests ? (
                    <div className="p-4 space-y-2">
                        {[1, 2, 3].map(i => (
                            <div key={i} className="h-10 bg-obsidian-elevated/50 rounded animate-pulse" />
                        ))}
                    </div>
                ) : manifests && manifests.length === 0 ? (
                    <div className="p-8 text-center text-(--theme-muted)">
                        No team manifests yet. Manifests are created from the team detail page.
                    </div>
                ) : manifests ? (
                    <table className="w-full text-sm">
                        <thead>
                            <tr className="border-b border-(--theme-muted)/10 text-(--theme-muted) text-xs">
                                <th className="px-3 py-2 text-left font-medium w-8" />
                                <th className="px-3 py-2 text-left font-medium">Team</th>
                                <th className="px-3 py-2 text-left font-medium">Human lead</th>
                                <th className="px-3 py-2 text-right font-medium">Daily cap</th>
                                <th className="px-3 py-2 text-right font-medium">Min authority</th>
                                <th className="px-3 py-2 text-right font-medium">Capabilities</th>
                                <th className="px-3 py-2 text-right font-medium">Agents</th>
                            </tr>
                        </thead>
                        <tbody>
                            {manifests.map(m => {
                                const isExpanded = expanded.has(m.teamId);
                                const Chevron = isExpanded ? LuChevronDown : LuChevronRight;
                                return (
                                    <React.Fragment key={m.teamId}>
                                        <tr className="border-b border-(--theme-muted)/5 last:border-b-0">
                                            <td className="px-3 py-2">
                                                <button
                                                    type="button"
                                                    onClick={() => toggle(m.teamId)}
                                                    className="text-(--theme-muted) hover:text-(--theme-foreground)"
                                                    aria-label={isExpanded ? 'Collapse' : 'Expand'}
                                                >
                                                    <Chevron className="w-4 h-4" />
                                                </button>
                                            </td>
                                            <td className="px-3 py-2">
                                                <Link to={`/teams/${m.teamId}`} className="font-mono text-xs hover:underline">
                                                    {m.teamId}
                                                </Link>
                                            </td>
                                            <td className="px-3 py-2 text-xs">
                                                {m.humanLead
                                                    ? <span className="font-mono">{m.humanLead}</span>
                                                    : <span className="text-(--theme-muted)">—</span>}
                                            </td>
                                            <td className="px-3 py-2 text-xs text-right whitespace-nowrap">
                                                {formatUsd(m.maxDailySpend)}
                                            </td>
                                            <td className="px-3 py-2 text-xs text-right whitespace-nowrap">
                                                {formatUsd(m.minSpendingAuthority)}
                                            </td>
                                            <td className="px-3 py-2 text-xs text-right">
                                                {m.allowedCapabilities?.length ?? 0}
                                            </td>
                                            <td className="px-3 py-2 text-xs text-right">{agentCount(m)}</td>
                                        </tr>
                                        {isExpanded && (
                                            <tr className="border-b border-(--theme-muted)/5 last:border-b-0 bg-obsidian-elevated/20">
                                                <td colSpan={7} className="px-6 py-3">
                                                    <ManifestDetail manifest={m} />
                                                </td>
                                            </tr>
                                        )}
                                    </React.Fragment>
                                );
                            })}
                        </tbody>
                    </table>
                ) : null}
            </div>
        </PageContainer>
    );
};

const ManifestDetail: React.FC<{ manifest: TeamManifest }> = ({ manifest }) => {
    const agents = Object.entries(manifest.agents ?? {});
    return (
        <div className="space-y-3">
            <div>
                <div className="text-[11px] uppercase tracking-wide text-(--theme-muted) mb-1">
                    Allowed capabilities ({manifest.allowedCapabilities?.length ?? 0})
                </div>
                {manifest.allowedCapabilities && manifest.allowedCapabilities.length > 0 ? (
                    <div className="flex flex-wrap gap-1.5">
                        {manifest.allowedCapabilities.map(cap => (
                            <Badge key={cap} variant="neutral" className="text-[10px] font-mono">{cap}</Badge>
                        ))}
                    </div>
                ) : (
                    <div className="text-xs text-(--theme-muted)">None</div>
                )}
            </div>

            <div>
                <div className="text-[11px] uppercase tracking-wide text-(--theme-muted) mb-1">
                    Agent roles ({agents.length})
                </div>
                {agents.length > 0 ? (
                    <table className="w-full text-xs">
                        <thead>
                            <tr className="text-(--theme-muted)">
                                <th className="px-2 py-1 text-left font-medium">Agent</th>
                                <th className="px-2 py-1 text-left font-medium">Role</th>
                                <th className="px-2 py-1 text-left font-medium">Capabilities</th>
                                <th className="px-2 py-1 text-left font-medium">PII redaction</th>
                            </tr>
                        </thead>
                        <tbody>
                            {agents.map(([key, role]) => (
                                <tr key={key} className="border-t border-(--theme-muted)/5">
                                    <td className="px-2 py-1">
                                        <Link
                                            to={`/agents/${role.agentId ?? key}`}
                                            className="font-mono hover:underline"
                                        >
                                            {role.agentId ?? key}
                                        </Link>
                                    </td>
                                    <td className="px-2 py-1 font-mono">{role.role ?? '—'}</td>
                                    <td className="px-2 py-1">
                                        {role.capabilities && role.capabilities.length > 0
                                            ? role.capabilities.join(', ')
                                            : <span className="text-(--theme-muted)">—</span>}
                                    </td>
                                    <td className="px-2 py-1">
                                        {role.requiresPiiRedaction
                                            ? <Badge variant="warning" className="text-[10px]">required</Badge>
                                            : <span className="text-(--theme-muted)">no</span>}
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                ) : (
                    <div className="text-xs text-(--theme-muted)">No agents bound to this manifest.</div>
                )}
            </div>
        </div>
    );
};
