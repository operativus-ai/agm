import React, { useState } from 'react';
import { useUpdateAgentBaseline, useAgentList } from '../hooks/useFinOps';
import { formatUsd } from '../utils/formatCurrency';
import { Typography } from '../../../shared/components/ui/Typography';
import { cn } from '../../../shared/utils/cn';

export const AgentBaselineConfiguration: React.FC = () => {
    const updateBaseline = useUpdateAgentBaseline();
    const { data: agents = [], isLoading: agentsLoading } = useAgentList();
    const [selectedAgentId, setSelectedAgentId] = useState<string>('');
    const [baselineValue, setBaselineValue] = useState<string>('');
    const [successMessage, setSuccessMessage] = useState<string | null>(null);

    const handleSave = (e: React.FormEvent<HTMLFormElement>) => {
        e.preventDefault();
        setSuccessMessage(null);

        const baselineUsdPerHour = parseFloat(baselineValue);
        if (!selectedAgentId || isNaN(baselineUsdPerHour) || baselineUsdPerHour <= 0) return;

        updateBaseline.mutate({ agentId: selectedAgentId, baselineUsdPerHour }, {
            onSuccess: () => {
                const agentName = agents.find(a => a.agentId === selectedAgentId)?.name ?? selectedAgentId;
                setSuccessMessage(`Guardrail established for ${agentName} at ${formatUsd(baselineUsdPerHour)}/hr`);
                setSelectedAgentId('');
                setBaselineValue('');
                setTimeout(() => setSuccessMessage(null), 4000);
            }
        });
    };

    return (
        <div className="card bg-obsidian-elevated border border-obsidian-stroke shadow-sm rounded-box overflow-hidden">
            <div className="card-body p-6">
                <Typography.Heading level={3} className="mb-2">Agent Baseline Guardrails</Typography.Heading>
                <Typography.Text className="text-sm text-theme-muted mb-6">
                    Assign a nominal USD/hr execution ceiling to a specific agent. Anomalous expenditures over this velocity trigger automatic Human-in-the-Loop pause interceptions.
                </Typography.Text>

                <form onSubmit={handleSave} className="flex flex-col sm:flex-row items-end gap-4">
                    {/* Agent Selector — populated from backend registry */}
                    <div className="form-control w-full sm:flex-1">
                        <label className="label pt-0 pb-1">
                            <span className="label-text text-xs uppercase tracking-widest font-semibold opacity-70">Target Agent</span>
                        </label>
                        <select
                            className="select select-bordered w-full bg-obsidian-surface"
                            value={selectedAgentId}
                            onChange={(e) => setSelectedAgentId(e.target.value)}
                            required
                            disabled={agentsLoading}
                        >
                            <option value="" disabled>
                                {agentsLoading ? 'Loading agents…' : 'Select an agent'}
                            </option>
                            {agents.map((agent) => (
                                <option key={agent.agentId} value={agent.agentId}>
                                    {agent.name} ({agent.agentId})
                                </option>
                            ))}
                        </select>
                    </div>

                    {/* Velocity Cap */}
                    <div className="form-control w-full sm:w-48">
                        <label className="label pt-0 pb-1">
                            <span className="label-text text-xs uppercase tracking-widest font-semibold opacity-70">Velocity Cap ($/hr)</span>
                        </label>
                        <label className="input input-bordered flex items-center gap-2 bg-obsidian-surface">
                            <span className="opacity-50 font-mono">$</span>
                            <input
                                type="number"
                                step="0.01"
                                min="0.01"
                                placeholder="5.00"
                                required
                                className="w-full"
                                value={baselineValue}
                                onChange={(e) => setBaselineValue(e.target.value)}
                            />
                        </label>
                    </div>

                    <button
                        type="submit"
                        className={cn("btn btn-primary min-w-[120px]", updateBaseline.isPending && "loading")}
                        disabled={updateBaseline.isPending || !selectedAgentId}
                    >
                        Apply Rule
                    </button>
                </form>

                {/* Success feedback */}
                {successMessage && (
                    <div className="mt-4 p-3 rounded bg-success/10 border border-success/30 text-success text-sm flex items-center gap-2 animate-in fade-in transition-all">
                        <svg xmlns="http://www.w3.org/2000/svg" className="h-4 w-4 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
                        </svg>
                        {successMessage}
                    </div>
                )}

                {/* Error feedback */}
                {updateBaseline.isError && (
                    <div className="mt-4 p-3 rounded bg-error/10 border border-error/30 text-error text-sm flex items-center gap-2 animate-in fade-in transition-all">
                        <svg xmlns="http://www.w3.org/2000/svg" className="h-4 w-4 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                        </svg>
                        Failed to persist guardrail. API sync error.
                    </div>
                )}
            </div>
        </div>
    );
};
