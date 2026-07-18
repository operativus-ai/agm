import React, { useState } from 'react';
import { Button } from '../../../shared/components/ui/Button';
import { SearchableSelect } from '../../../shared/components/ui/SearchableSelect';
import { LuSend } from 'react-icons/lu';
import type { RemoteAgentRegistration } from '../api/a2aApi';

interface Props {
    peers: RemoteAgentRegistration[];
    peersLoading: boolean;
    disabled?: boolean;
    /** Caller submits — the panel just gathers input. Returns a Promise so we
     *  can render a spinner. Resolve = clear form; reject = leave form alone
     *  (caller is responsible for the error toast). */
    onSubmit: (params: { targetAgentId: string; alias: string; input: string }) => Promise<void>;
}

export const A2aTaskSubmitPanel: React.FC<Props> = ({ peers, peersLoading, disabled, onSubmit }) => {
    const [targetAlias, setTargetAlias] = useState('');
    const [input, setInput] = useState('');
    const [submitting, setSubmitting] = useState(false);
    const [error, setError] = useState<string | null>(null);

    // Peer picker options. Backend resolves the task by the remoteAgentId of the
    // selected peer; we key on alias because it's the human-facing identifier
    // and is guaranteed unique within an org.
    const peerOptions = peers.map(p => ({
        value: p.alias,
        label: p.alias,
        sublabel: p.remoteAgentId,
    }));
    const selectedPeer = peers.find(p => p.alias === targetAlias);

    const handleSubmit = async () => {
        if (!selectedPeer || !input.trim()) return;
        setError(null);
        setSubmitting(true);
        try {
            await onSubmit({
                targetAgentId: selectedPeer.remoteAgentId,
                alias: selectedPeer.alias,
                input: input.trim(),
            });
            setInput('');
            // Keep peer selection so the operator can fire a follow-up to the
            // same target without re-picking.
        } catch (err: unknown) {
            setError(err instanceof Error ? err.message : 'Submission failed.');
        } finally {
            setSubmitting(false);
        }
    };

    return (
        <div className="bg-(--theme-card) border border-(--theme-muted)/10 rounded-xl p-5 space-y-4 shadow-sm">
            <div>
                <h3 className="text-sm font-bold uppercase tracking-wider text-(--theme-muted) flex items-center gap-2">
                    <LuSend className="w-3.5 h-3.5" /> Submit A2A Task
                </h3>
                <p className="text-xs text-(--theme-muted) mt-1">
                    Dispatch a task to a registered peer agent. Status updates stream back via SSE and appear in the Active Tasks panel below.
                </p>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                <SearchableSelect
                    label="Target peer"
                    value={targetAlias}
                    onChange={setTargetAlias}
                    options={peerOptions}
                    loading={peersLoading}
                    placeholder="Pick a registered peer…"
                    emptyMessage={peersLoading ? 'Loading peers…' : 'No peers registered. Register one above first.'}
                />
                <div className="text-xs text-(--theme-muted) self-end pb-2 font-mono truncate" title={selectedPeer?.baseUrl ?? ''}>
                    {selectedPeer && (
                        <>
                            <span className="text-[10px] uppercase tracking-wider text-(--theme-muted)/60 block">Endpoint</span>
                            <span className="text-(--theme-foreground)">{selectedPeer.baseUrl}</span>
                        </>
                    )}
                </div>
            </div>

            <div>
                <label className="label py-1">
                    <span className="text-xs font-bold text-(--theme-muted) uppercase tracking-wider">Task input</span>
                    <span className="text-[10px] text-error">*</span>
                </label>
                <textarea
                    className="textarea textarea-bordered h-24 w-full text-sm font-mono"
                    value={input}
                    onChange={(e) => setInput(e.target.value)}
                    placeholder="Plain text sent as the task input. Peer agents may parse it as JSON if they expect that shape."
                    disabled={submitting || disabled}
                />
            </div>

            {error && <div className="text-xs text-error font-medium">{error}</div>}

            <div className="flex justify-end">
                <Button
                    size="sm"
                    className="gap-1.5"
                    disabled={!selectedPeer || !input.trim() || submitting || disabled}
                    onClick={handleSubmit}
                >
                    {submitting
                        ? <span className="loading loading-spinner loading-xs" />
                        : <LuSend className="w-3.5 h-3.5" />
                    }
                    Dispatch
                </Button>
            </div>
        </div>
    );
};
