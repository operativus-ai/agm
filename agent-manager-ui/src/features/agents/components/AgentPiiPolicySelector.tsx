import React, { useState, useEffect } from 'react';
import { piiPolicyApi } from '../../security/api/piiPolicyApi';
import type { PiiPolicy } from '../../security/types/pii.types';
import { logger } from '../../../utils/logger';

interface AgentPiiPolicySelectorProps {
    agentId: string;
}

/**
 * Renders a checklist of available PII policies with toggle bindings per agent.
 * Designed to be mounted inside the Security tab of AgentFormModal/AgentEditModal,
 * conditionally visible when `requiresPiiRedaction` is toggled ON.
 */
export const AgentPiiPolicySelector: React.FC<AgentPiiPolicySelectorProps> = ({ agentId }) => {
    const [allPolicies, setAllPolicies] = useState<PiiPolicy[]>([]);
    const [boundPolicyIds, setBoundPolicyIds] = useState<Set<string>>(new Set());
    const [loading, setLoading] = useState(true);
    const [toggling, setToggling] = useState<string | null>(null);

    useEffect(() => {
        loadData();
    }, [agentId]);

    const loadData = async () => {
        setLoading(true);
        try {
            const [policies, bindings] = await Promise.all([
                piiPolicyApi.getPolicies(),
                agentId ? piiPolicyApi.getAgentBindings(agentId) : Promise.resolve([])
            ]);
            setAllPolicies(policies.filter(p => p.enabled));
            setBoundPolicyIds(new Set(bindings));
        } catch (err) {
            logger.error('Failed to load PII policy bindings', err);
        } finally {
            setLoading(false);
        }
    };


    if (loading) {
        return (
            <div className="flex items-center gap-2 p-3 text-sm text-theme-muted">
                <span className="loading loading-spinner loading-xs"></span>
                Loading PII policies...
            </div>
        );
    }

    if (allPolicies.length === 0) {
        return (
            <div className="text-xs text-theme-muted p-3 border border-dashed border-obsidian-stroke rounded-lg">
                No active PII policies defined. Visit Security → Data Privacy to create policies.
            </div>
        );
    }

    const groupedPolicies = allPolicies.reduce((acc, policy) => {
        const fw = policy.complianceFramework || 'STANDARD';
        if (!acc[fw]) acc[fw] = [];
        acc[fw].push(policy);
        return acc;
    }, {} as Record<string, typeof allPolicies>);

    return (
        <div className="mt-2 space-y-3">
            <div className="text-xs text-theme-muted mb-2 flex items-center gap-2">
                <span>Compliance Framework Overrides</span>
                {boundPolicyIds.size === 0 && (
                    <span className="badge badge-ghost badge-xs">Using global defaults</span>
                )}
            </div>
            {Object.entries(groupedPolicies).map(([fw, fwPolicies]) => {
                const fwPolicyIds = fwPolicies.map(p => p.id);
                const isFullyBound = fwPolicies.every(p => boundPolicyIds.has(p.id));
                const isPartiallyBound = !isFullyBound && fwPolicies.some(p => boundPolicyIds.has(p.id));

                return (
                    <div key={fw} className={`border rounded-lg p-3 transition-colors ${isFullyBound ? 'border-warn-amber/50 bg-warn-amber/5' : 'border-obsidian-stroke bg-obsidian-surface hover:border-agent-blue/50'}`}>
                        <label className="cursor-pointer flex flex-col gap-3">
                            <div className="flex items-center justify-between">
                                <span className="font-semibold text-sm text-theme-foreground">{fw.replace('_', ' ')} Framework</span>
                                <div className="flex items-center gap-2">
                                    <span className="badge badge-xs badge-neutral">{fwPolicies.length} Rules</span>
                                    {toggling === fw && <span className="loading loading-spinner loading-xs"></span>}
                                </div>
                            </div>
                            <input
                                type="checkbox"
                                className="toggle toggle-sm toggle-warning"
                                checked={isFullyBound}
                                onChange={async () => {
                                    if (!agentId) return;
                                    setToggling(fw);
                                    try {
                                        if (isFullyBound || isPartiallyBound) {
                                            await Promise.all(fwPolicyIds.map(id => boundPolicyIds.has(id) ? piiPolicyApi.unbindPolicy(agentId, id) : Promise.resolve()));
                                            setBoundPolicyIds(prev => {
                                                const next = new Set(prev);
                                                fwPolicyIds.forEach(id => next.delete(id));
                                                return next;
                                            });
                                        } else {
                                            await Promise.all(fwPolicyIds.map(id => piiPolicyApi.bindPolicy(agentId, id)));
                                            setBoundPolicyIds(prev => {
                                                const next = new Set(prev);
                                                fwPolicyIds.forEach(id => next.add(id));
                                                return next;
                                            });
                                        }
                                    } catch(e) {
                                        logger.error('Failed macro toggle', e);
                                        alert('Failed to apply framework bindings.');
                                    } finally {
                                        setToggling(null);
                                    }
                                }}
                                disabled={toggling === fw}
                            />
                        </label>

                        <div className="mt-2 flex flex-col gap-1.5 border-t border-obsidian-stroke border-dashed pt-2">
                            {fwPolicies.map(policy => (
                                <div key={policy.id} className="flex justify-between items-center text-xs text-theme-muted">
                                    <span>{policy.name}</span>
                                    <div className="flex gap-2">
                                        <span className="font-mono text-[9px]">{policy.patternType}</span>
                                        <span className={`text-[9px] font-semibold ${policy.scrubStrategy === 'FPE' ? 'text-info' : 'text-warning'}`}>
                                            {policy.scrubStrategy}
                                        </span>
                                    </div>
                                </div>
                            ))}
                        </div>
                    </div>
                );
            })}
        </div>
    );
};
