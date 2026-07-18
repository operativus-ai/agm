import React, { useState, useEffect, useCallback } from 'react';
import { piiPolicyApi } from '../api/piiPolicyApi';
import type { PiiPolicy } from '../types/pii.types';
import { CreatePiiPolicyModal } from './CreatePiiPolicyModal';
import { logger } from '../../../utils/logger';

export const PiiPolicyManager: React.FC = () => {
    const [policies, setPolicies] = useState<PiiPolicy[]>([]);
    const [loading, setLoading] = useState(true);
    const [isCreateOpen, setIsCreateOpen] = useState(false);

    const loadPolicies = useCallback(async () => {
        setLoading(true);
        try {
            const data = await piiPolicyApi.getPolicies();
            setPolicies(data);
        } catch (err) {
            logger.error('Failed to load PII policies', err);
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        loadPolicies();
    }, [loadPolicies]);

    const handleDelete = async (id: string, name: string) => {
        if (!confirm(`Delete PII policy "${name}"? This will also remove all agent bindings for this policy.`)) return;
        try {
            await piiPolicyApi.deletePolicy(id);
            setPolicies(prev => prev.filter(p => p.id !== id));
        } catch (err) {
            logger.error('Failed to delete policy', err);
            alert('Failed to delete policy.');
        }
    };

    return (
        <div className="bg-base-100 border border-obsidian-stroke rounded-box overflow-hidden shadow-sm">
            <div className="p-4 border-b border-base-200 bg-warning/10 text-warning flex justify-between items-center">
                <div>
                    <h3 className="font-bold text-lg">PII Policy Dictionary</h3>
                    <p className="text-xs opacity-70">Global detection rules applied during agent execution</p>
                </div>
                <button className="btn btn-sm btn-warning" onClick={() => setIsCreateOpen(true)}>
                    + New Policy
                </button>
            </div>

            <div className="overflow-x-auto w-full">
                {loading ? (
                    <div className="flex justify-center p-8">
                        <span className="loading loading-spinner loading-md text-warning"></span>
                    </div>
                ) : policies.length === 0 ? (
                    <div className="text-center py-8 text-base-content/50 text-sm">
                        No PII policies defined. Create one to begin protecting sensitive data.
                    </div>
                ) : (
                    <div className="flex flex-col gap-2 p-4">
                        {Object.entries(
                            policies.reduce((acc, policy) => {
                                const fw = policy.complianceFramework || 'STANDARD';
                                if (!acc[fw]) acc[fw] = [];
                                acc[fw].push(policy);
                                return acc;
                            }, {} as Record<string, typeof policies>)
                        ).map(([framework, fwPolicies]) => (
                            <div key={framework} className="collapse collapse-arrow bg-obsidian-elevated border border-obsidian-stroke">
                                <input type="checkbox" defaultChecked={framework === 'STANDARD' || framework === 'HIPAA'} />
                                <div className="collapse-title text-base font-semibold flex items-center gap-2">
                                    <span className="badge badge-neutral">{framework.replace('_', ' ')}</span>
                                    <span className="text-sm font-normal opacity-70">({fwPolicies.length} policies)</span>
                                </div>
                                <div className="collapse-content bg-base-100 p-0 border-t border-obsidian-stroke">
                                    <table className="table w-full">
                                        <thead>
                                            <tr className="bg-obsidian-elevated/50">
                                                <th>Name</th>
                                                <th>Category</th>
                                                <th>Strategy</th>
                                                <th>Status</th>
                                                <th className="w-20 text-right">Actions</th>
                                            </tr>
                                        </thead>
                                        <tbody>
                                            {fwPolicies.map((policy) => (
                                                <tr key={policy.id} className="hover text-xs">
                                                    <td>
                                                        <div className="font-medium text-sm">{policy.name}</div>
                                                        {policy.description && (
                                                            <div className="text-[10px] opacity-60 mt-0.5">{policy.description}</div>
                                                        )}
                                                    </td>
                                                    <td>
                                                        <span className="badge badge-ghost badge-sm">{policy.taxonomicCategory || 'UNCATEGORIZED'}</span>
                                                    </td>
                                                    <td>
                                                        <div className="flex flex-col gap-1">
                                                            <span className="badge badge-ghost badge-xs font-mono">{policy.patternType}</span>
                                                            <span className={`badge badge-xs ${policy.scrubStrategy === 'FPE' ? 'badge-info' : 'badge-warning'}`}>
                                                                {policy.scrubStrategy}
                                                            </span>
                                                        </div>
                                                    </td>
                                                    <td>
                                                        {policy.enabled ? (
                                                            <span className="badge badge-success badge-sm gap-1">
                                                                <span className="w-1.5 h-1.5 rounded-full bg-success-content"></span>
                                                                Active
                                                            </span>
                                                        ) : (
                                                            <span className="badge badge-ghost badge-sm">Disabled</span>
                                                        )}
                                                    </td>
                                                    <td className="text-right">
                                                        <button
                                                            className="btn btn-xs btn-ghost text-error"
                                                            onClick={() => handleDelete(policy.id, policy.name)}
                                                        >
                                                            Delete
                                                        </button>
                                                    </td>
                                                </tr>
                                            ))}
                                        </tbody>
                                    </table>
                                </div>
                            </div>
                        ))}
                    </div>
                )}
            </div>

            <CreatePiiPolicyModal
                isOpen={isCreateOpen}
                onClose={() => setIsCreateOpen(false)}
                onCreated={loadPolicies}
            />
        </div>
    );
};
