import React from 'react';
import { Button } from '../../../shared/components/ui/Button';
import { useEscapeToClose } from '../../../shared/hooks/useEscapeToClose';
import type { AgentConfig } from '../../../shared/types/api';

interface SettingsImpactModalProps {
    impactedAgents: AgentConfig[];
    saving: boolean;
    onCancel: () => void;
    onConfirm: () => void;
}

/**
 * "Blast Radius" cascading-change warning for SettingsPage. Lists the active
 * agents that inherit the global defaults about to change. Extracted from
 * SettingsPage to keep that page a thinner assembler — pure presentational,
 * owns no state.
 */
export const SettingsImpactModal: React.FC<SettingsImpactModalProps> = ({
    impactedAgents,
    saving,
    onCancel,
    onConfirm,
}) => {
    useEscapeToClose(onCancel);
    return (
        <div className="modal modal-open">
            <div className="modal-box bg-(--theme-card) border border-(--theme-muted)/10 max-w-lg">
                <h3 className="font-bold text-lg text-(--theme-foreground)">Cascading Change Warning</h3>
                <p className="py-2 text-sm text-(--theme-muted)">
                    This change will cascade to <strong className="text-warning">{impactedAgents.length} active agent{impactedAgents.length !== 1 ? 's' : ''}</strong> that inherit global defaults.
                </p>
                <div className="max-h-48 overflow-y-auto border border-(--theme-muted)/10 rounded-lg mt-2">
                    <table className="table table-xs w-full">
                        <thead>
                            <tr className="text-(--theme-muted)">
                                <th>Agent</th>
                                <th>Inherits</th>
                            </tr>
                        </thead>
                        <tbody>
                            {impactedAgents.map(a => (
                                <tr key={a.agentId}>
                                    <td className="font-mono text-xs">{a.name}</td>
                                    <td className="text-xs text-(--theme-muted)">
                                        {[
                                            a.temperature == null && 'temp',
                                            a.topP == null && 'topP',
                                            a.finOpsRiskTier == null && 'finops',
                                            a.securityTier == null && 'security',
                                            a.maxConcurrentExecutions == null && 'concurrency',
                                        ].filter(Boolean).join(', ')}
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
                <div className="modal-action">
                    <Button variant="ghost" size="sm" onClick={onCancel} disabled={saving}>Cancel</Button>
                    <Button variant="primary" size="sm" onClick={onConfirm} disabled={saving} className="bg-warning text-warning-content hover:bg-warning/80">
                        {saving ? <span className="loading loading-spinner loading-xs"></span> : `Apply to ${impactedAgents.length} Agents`}
                    </Button>
                </div>
            </div>
            <div className="modal-backdrop" onClick={onCancel}></div>
        </div>
    );
};
