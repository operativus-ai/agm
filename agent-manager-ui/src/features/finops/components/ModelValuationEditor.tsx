import React, { useCallback, useState } from 'react';
import { useValuationRates, useUpdateValuationRate } from '../hooks/useFinOps';
import type { ValuationRate } from '../api/finopsApi';
import { Typography } from '../../../shared/components/ui/Typography';
import { cn } from '../../../shared/utils/cn';

/**
 * Tracks local edits: maps modelId -> per-field string values.
 * Only models with pending changes appear in this map.
 */
interface PendingEdit {
    inputRatePerKTokens: string;
    outputRatePerKTokens: string;
    cachedInputRatePerKTokens: string;
    reasoningRatePerKTokens: string;
}

type RateField = keyof PendingEdit;

export const ModelValuationEditor: React.FC = () => {
    const { data: rates = [], isLoading } = useValuationRates();
    const updateRate = useUpdateValuationRate();
    const [pendingEdits, setPendingEdits] = useState<Record<string, PendingEdit>>({});
    const [commitStatus, setCommitStatus] = useState<'idle' | 'committing' | 'success' | 'error'>('idle');

    const hasPendingChanges = Object.keys(pendingEdits).length > 0;

    /** Mark a field as locally modified */
    const handleFieldChange = useCallback((modelId: string, field: RateField, value: string) => {
        setPendingEdits(prev => {
            const existing = prev[modelId];
            const original = rates.find(r => r.modelId === modelId);
            if (!original) return prev;

            const baseline: PendingEdit = {
                inputRatePerKTokens:       String(original.inputRatePerKTokens),
                outputRatePerKTokens:      String(original.outputRatePerKTokens),
                cachedInputRatePerKTokens: String(original.cachedInputRatePerKTokens),
                reasoningRatePerKTokens:   String(original.reasoningRatePerKTokens),
            };

            const updated: PendingEdit = existing
                ? { ...existing, [field]: value }
                : { ...baseline, [field]: value };

            // Remove from pending if all fields match original
            const anyChanged =
                parseFloat(updated.inputRatePerKTokens)       !== original.inputRatePerKTokens       ||
                parseFloat(updated.outputRatePerKTokens)      !== original.outputRatePerKTokens      ||
                parseFloat(updated.cachedInputRatePerKTokens) !== original.cachedInputRatePerKTokens ||
                parseFloat(updated.reasoningRatePerKTokens)   !== original.reasoningRatePerKTokens;

            if (!anyChanged) {
                const { [modelId]: _, ...rest } = prev;
                return rest;
            }
            return { ...prev, [modelId]: updated };
        });
    }, [rates]);

    /** Commit all pending changes sequentially */
    const handleCommitAll = useCallback(async () => {
        setCommitStatus('committing');
        const entries = Object.entries(pendingEdits);
        try {
            for (const [modelId, edit] of entries) {
                await updateRate.mutateAsync({
                    modelId,
                    inputRatePerKTokens:       parseFloat(edit.inputRatePerKTokens),
                    outputRatePerKTokens:      parseFloat(edit.outputRatePerKTokens),
                    cachedInputRatePerKTokens: parseFloat(edit.cachedInputRatePerKTokens),
                    reasoningRatePerKTokens:   parseFloat(edit.reasoningRatePerKTokens),
                });
            }
            setPendingEdits({});
            setCommitStatus('success');
            setTimeout(() => setCommitStatus('idle'), 3000);
        } catch {
            setCommitStatus('error');
            setTimeout(() => setCommitStatus('idle'), 4000);
        }
    }, [pendingEdits, updateRate]);

    /** Discard all pending edits */
    const handleDiscardAll = useCallback(() => {
        setPendingEdits({});
    }, []);

    /** Get the displayed value for a cell — pending edit or original */
    const getDisplayValue = (rate: ValuationRate, field: RateField): string => {
        const pending = pendingEdits[rate.modelId];
        if (pending) return pending[field];
        return String(rate[field]);
    };

    const isEdited = (modelId: string): boolean => modelId in pendingEdits;

    return (
        <div className="card bg-obsidian-elevated border border-obsidian-stroke shadow-sm rounded-box overflow-hidden h-full flex flex-col">
            <div className="card-body p-6 flex flex-col h-full">
                {/* Header Row */}
                <div className="flex justify-between items-start mb-4">
                    <div>
                        <Typography.Heading level={3}>Model Valuation Rates</Typography.Heading>
                        <Typography.Text className="text-sm text-theme-muted mt-1">
                            Token-to-USD pricing table. Modify rates inline and commit in batch.
                            Cached &amp; Reasoning rates override the base rate for those token types (0 = use base rate).
                        </Typography.Text>
                    </div>
                    <div className="flex gap-2 shrink-0">
                        {hasPendingChanges && (
                            <button
                                type="button"
                                onClick={handleDiscardAll}
                                className="btn btn-sm btn-ghost text-base-content/60"
                                disabled={commitStatus === 'committing'}
                            >
                                Discard
                            </button>
                        )}
                        <button
                            type="button"
                            onClick={handleCommitAll}
                            className={cn(
                                "btn btn-sm btn-primary",
                                commitStatus === 'committing' && "loading",
                                !hasPendingChanges && "btn-disabled opacity-40"
                            )}
                            disabled={!hasPendingChanges || commitStatus === 'committing'}
                        >
                            {hasPendingChanges
                                ? `Commit ${Object.keys(pendingEdits).length} Update${Object.keys(pendingEdits).length > 1 ? 's' : ''}`
                                : 'No Changes'}
                        </button>
                    </div>
                </div>

                {/* Status Toast */}
                {commitStatus === 'success' && (
                    <div className="p-2 rounded bg-success/10 border border-success/30 text-success text-sm flex items-center gap-2 mb-4 animate-in fade-in">
                        <svg xmlns="http://www.w3.org/2000/svg" className="h-4 w-4 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
                        </svg>
                        All rate updates persisted to database.
                    </div>
                )}
                {commitStatus === 'error' && (
                    <div className="p-2 rounded bg-error/10 border border-error/30 text-error text-sm flex items-center gap-2 mb-4 animate-in fade-in">
                        <svg xmlns="http://www.w3.org/2000/svg" className="h-4 w-4 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                        </svg>
                        Failed to commit some updates. Check connectivity.
                    </div>
                )}

                {/* Data Table */}
                {isLoading ? (
                    <div className="animate-pulse h-32 bg-obsidian-surface rounded-md" />
                ) : (
                    <div className="overflow-x-auto rounded-md border border-obsidian-stroke flex-1">
                        <table className="table table-zebra w-full text-sm">
                            <thead className="bg-obsidian-elevated text-base-content/80">
                                <tr>
                                    <th>Model ID</th>
                                    <th>Input (per 1K)</th>
                                    <th>Output (per 1K)</th>
                                    <th title="Discounted rate for cached prompt tokens. 0 = billed at input rate.">
                                        Cached Input (per 1K)
                                    </th>
                                    <th title="Rate for reasoning/thinking tokens (o1, claude-3-7). 0 = billed at output rate.">
                                        Reasoning (per 1K)
                                    </th>
                                </tr>
                            </thead>
                            <tbody>
                                {rates.length === 0 ? (
                                    <tr>
                                        <td colSpan={5} className="text-center text-base-content/50 py-8">
                                            No valuation rates configured. Seed data may be pending.
                                        </td>
                                    </tr>
                                ) : (
                                    rates.map((rate) => {
                                        const edited = isEdited(rate.modelId);
                                        return (
                                            <tr key={rate.modelId} className={cn(edited && "bg-primary/5")}>
                                                <td className="font-mono text-sm">
                                                    <div className="flex items-center gap-2">
                                                        {edited && (
                                                            <span className="w-1.5 h-1.5 rounded-full bg-primary shrink-0" title="Modified" />
                                                        )}
                                                        {rate.modelId}
                                                    </div>
                                                </td>
                                                {(
                                                    [
                                                        'inputRatePerKTokens',
                                                        'outputRatePerKTokens',
                                                        'cachedInputRatePerKTokens',
                                                        'reasoningRatePerKTokens',
                                                    ] as RateField[]
                                                ).map((field) => (
                                                    <td key={field}>
                                                        <label className="input input-sm input-bordered flex items-center gap-2 bg-obsidian-surface w-32">
                                                            <span className="opacity-50 font-mono text-xs">$</span>
                                                            <input
                                                                type="number"
                                                                step="0.001"
                                                                min="0"
                                                                className="w-full font-mono"
                                                                value={getDisplayValue(rate, field)}
                                                                onChange={(e) => handleFieldChange(rate.modelId, field, e.target.value)}
                                                            />
                                                        </label>
                                                    </td>
                                                ))}
                                            </tr>
                                        );
                                    })
                                )}
                            </tbody>
                        </table>
                    </div>
                )}
            </div>
        </div>
    );
};
