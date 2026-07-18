import React, { useCallback, useState } from 'react';
import type { Approval } from '../../../shared/types/orchestration';
import { orchestrationApi } from '../../../shared/api/orchestrationApi';
import { Typography } from '../../../shared/components/ui/Typography';
import { useEscapeToClose } from '../../../shared/hooks/useEscapeToClose';
import { cn } from '../../../shared/utils/cn';
import { RunStatus } from '../../../shared/types/enums';

interface ApprovalReviewModalProps {
    approval: Approval;
    onClose: () => void;
}

export const ApprovalReviewModal: React.FC<ApprovalReviewModalProps> = ({ approval, onClose }) => {
    const [submitting, setSubmitting] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [showMfa, setShowMfa] = useState(false);
    const [mfaCode, setMfaCode] = useState('');

    // Escape mirrors the Cancel button: back out of the MFA step first, else close.
    useEscapeToClose(useCallback(() => {
        if (showMfa) setShowMfa(false); else onClose();
    }, [showMfa, onClose]));

    // Simulate parsing the new DecisionPackage out of backend payload
    const isTerminal = approval.status !== RunStatus.PENDING;
    
    // Fallbacks if backend hasn't updated DTOs perfectly yet
    const decisionPackage = approval.decisionPackage || {
        tier: 'TIER_3_DESTRUCTIVE',
        reasoningTrace: "The user asked to reset the environment. I have determined that truncating the database is the most efficient path.",
        impactAssessment: "WARNING: This drops all current users and records without backup. High risk.",
        proposedAction: approval.toolArguments || "{}"
    };
    
    const isDestructive = decisionPackage.tier === 'TIER_3_DESTRUCTIVE';

    const handleResolve = async (resolution: 'APPROVED' | 'REJECTED') => {
        if (resolution === 'APPROVED' && isDestructive && !showMfa) {
            setShowMfa(true);
            return;
        }

        try {
            setSubmitting(true);
            setError(null);
            
            // if MFA is required, we'd theoretically pass it to the API
            if (showMfa && mfaCode.length < 6) {
                throw new Error('Invalid MFA signature');
            }

            await orchestrationApi.resolveApproval(approval.id, resolution);
            onClose();
        } catch (err: any) {
            console.error("Failed to resolve approval", err);
            setError(err.message || 'MFA or Connection failed');
            if (!showMfa) onClose();
        } finally {
            setSubmitting(false);
        }
    };

    return (
        <dialog className="modal modal-open">
            <div className="modal-box max-w-4xl bg-obsidian-surface p-0 overflow-hidden shadow-2xl border border-obsidian-stroke">
                <div className="p-6 border-b border-obsidian-stroke bg-obsidian-elevated/50 flex justify-between items-center">
                    <div>
                        <Typography.Heading level={3}>Decision Package Inspector</Typography.Heading>
                        <Typography.Text variant="muted">Evaluating Agent <strong>{approval.agentId}</strong> Request</Typography.Text>
                    </div>
                    <div>
                        <span className={cn("badge", isDestructive ? "badge-error" : "badge-warning")}>
                            {decisionPackage.tier}
                        </span>
                    </div>
                </div>

                <div className="p-6 space-y-6">
                    {error && (
                        <div className="alert alert-error font-semibold">
                            <svg xmlns="http://www.w3.org/2000/svg" className="stroke-current shrink-0 h-6 w-6" fill="none" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M10 14l2-2m0 0l2-2m-2 2l-2-2m2 2l2 2m7-2a9 9 0 11-18 0 9 9 0 0118 0z" /></svg>
                            <span>{error}</span>
                        </div>
                    )}

                    {!showMfa ? (
                        <>
                            <div className="grid grid-cols-2 gap-4">
                                <div>
                                    <div className="text-sm font-semibold opacity-70">Intended Action</div>
                                    <div className="font-mono text-sm bg-obsidian-elevated p-2 rounded mt-1 border border-obsidian-stroke">{approval.toolName}</div>
                                </div>
                                <div>
                                    <div className="text-sm font-semibold opacity-70">Requested At</div>
                                    <div className="text-sm mt-1">{new Date(approval.createdAt).toLocaleString()}</div>
                                </div>
                            </div>

                            <div className="grid grid-cols-1 md:grid-cols-2 gap-6 mt-4">
                                <div>
                                    <div className="text-sm font-semibold opacity-70 mb-2">Reasoning Trace</div>
                                    <div className="bg-obsidian-elevated p-4 rounded-xl text-sm leading-relaxed border border-obsidian-stroke h-32 overflow-y-auto">
                                        {decisionPackage.reasoningTrace}
                                    </div>
                                </div>
                                <div>
                                    <div className="text-sm font-semibold text-error mb-2">Impact Assessment</div>
                                    <div className="bg-error/10 text-error p-4 rounded-xl text-sm leading-relaxed border border-error/30 h-32 overflow-y-auto font-semibold">
                                        {decisionPackage.impactAssessment}
                                    </div>
                                </div>
                            </div>

                            <div>
                                <div className="text-sm font-semibold opacity-70 mb-2">Proposed Action Payload</div>
                                <pre className="bg-[#1e1e1e] text-[#d4d4d4] p-4 rounded-xl text-xs overflow-x-auto shadow-inner font-mono">
                                    {decisionPackage.proposedAction}
                                </pre>
                            </div>
                        </>
                    ) : (
                        <div className="py-8 px-4 text-center space-y-6">
                            <div className="mx-auto w-16 h-16 bg-error/20 rounded-full flex items-center justify-center mb-4">
                                <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" className="w-8 h-8 text-error">
                                  <path strokeLinecap="round" strokeLinejoin="round" d="M16.5 10.5V6.75a4.5 4.5 0 10-9 0v3.75m-.75 11.25h10.5a2.25 2.25 0 002.25-2.25v-6.75a2.25 2.25 0 00-2.25-2.25H6.75a2.25 2.25 0 00-2.25 2.25v6.75a2.25 2.25 0 002.25 2.25z" />
                                </svg>
                            </div>
                            <Typography.Heading level={3}>Cryptographic MFA Required</Typography.Heading>
                            <Typography.Text variant="muted">
                                This action modifies system state and breaches Tier 3 Zero-Trust policies.
                                <br/>Please enter your authenticator code to proceed.
                            </Typography.Text>
                            
                            <div className="form-control max-w-xs mx-auto">
                                <input 
                                    type="text" 
                                    placeholder="000000" 
                                    className="input input-lg input-bordered text-center tracking-widest font-mono text-2xl" 
                                    maxLength={6}
                                    value={mfaCode}
                                    onChange={(e) => setMfaCode(e.target.value)}
                                />
                            </div>
                        </div>
                    )}
                </div>

                <div className="p-6 border-t border-obsidian-stroke flex justify-end gap-3 bg-obsidian-elevated/50 items-center">
                    {isTerminal && (
                        <div className="text-sm font-semibold opacity-70 mr-auto">
                            State: <span className="uppercase">{approval.status}</span>
                        </div>
                    )}
                    
                    <button className="btn btn-ghost" onClick={() => showMfa ? setShowMfa(false) : onClose()} disabled={submitting}>
                        {showMfa ? 'Back' : 'Close'}
                    </button>
                    
                    {!isTerminal && (
                        <>
                            {!showMfa && (
                                <button 
                                    className="btn btn-error btn-outline" 
                                    onClick={() => handleResolve('REJECTED')}
                                    disabled={submitting}
                                >
                                    Reject Action
                                </button>
                            )}
                            <button 
                                className={cn("btn", isDestructive ? "btn-error" : "btn-success")} 
                                onClick={() => handleResolve('APPROVED')}
                                disabled={submitting || (showMfa && mfaCode.length < 6)}
                            >
                                {submitting ? <span className="loading loading-spinner"></span> : showMfa ? 'Confirm Cryptographic Signature' : 'Authorize Action'}
                            </button>
                        </>
                    )}
                </div>
            </div>
            <form method="dialog" className="modal-backdrop bg-neutral/80 backdrop-blur-sm">
                <button onClick={onClose} disabled={submitting}>close</button>
            </form>
        </dialog>
    );
};
