import React, { useState } from 'react';
import type { SwarmEscalationApprovalPayload } from '../types';
import { ApprovalAction } from '../../../shared/types/enums';

interface EscalationApprovalCardProps {
  payload: SwarmEscalationApprovalPayload;
  onAction: (action: ApprovalAction) => void;
}

export const EscalationApprovalCard: React.FC<EscalationApprovalCardProps> = ({ payload, onAction }) => {
  const [showReasoning, setShowReasoning] = useState(false);

  return (
    <div className="mt-4 p-4 bg-obsidian-surface/80 backdrop-blur-md rounded-lg border border-error-red/40 shadow-lg relative overflow-hidden">
        {/* Subtle glassmorphism gradient background */}
        <div className="absolute inset-0 bg-linear-to-br from-obsidian-elevated/50 to-error-red/5 pointer-events-none" />
        
        <div className="relative z-10">
            <div className="flex items-center gap-2 mb-2">
                <span className="text-lg">🛡️</span>
                <p className="font-bold text-xs text-error-red">Security Tier Escalation</p>
            </div>
            <p className="text-xs text-theme-muted mb-2">
                Agent <span className="font-mono font-bold text-agent-blue">{payload.sourceAgentId}</span> (Tier {payload.sourceTier}) 
                is attempting to hand off to <span className="font-mono font-bold text-agent-blue">{payload.targetAgentId}</span> (Tier {payload.targetTier}).
            </p>
            <p className="text-[10px] text-theme-muted mb-3">This cross-tier transition requires human authorization before execution can continue.</p>

            {/* Reasoning Accordion / Output */}
            <div className="mb-4">
                <button 
                    onClick={() => setShowReasoning(!showReasoning)}
                    className="flex items-center gap-1 text-[10px] uppercase font-bold tracking-wider text-theme-muted hover:text-theme-foreground transition-colors"
                >
                    <svg className={`w-3 h-3 transition-transform ${showReasoning ? 'rotate-90' : ''}`} fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M9 5l7 7-7 7" /></svg>
                    View DAG Lineage &amp; Trace Reasoning
                </button>
                
                {showReasoning && (
                    <div className="mt-3 p-3 rounded-md bg-obsidian-elevated border border-obsidian-stroke/50 backdrop-blur-sm animate-in fade-in slide-in-from-top-1 text-xs">
                        {payload.reasoningLineage ? (
                            <div className="mb-3">
                                <span className="text-[9px] uppercase text-theme-muted block mb-1">Execution Graph</span>
                                <code className="block w-full overflow-x-auto whitespace-nowrap text-agent-blue bg-obsidian-base p-2 rounded text-[10px] font-mono">
                                    {payload.reasoningLineage}
                                </code>
                            </div>
                        ) : (
                           <div className="text-warn-amber text-[10px] italic mb-2">Reasoning context unavailable</div>
                        )}
                        <div className="flex justify-between items-center border-t border-obsidian-stroke/50 pt-2 text-[9px] text-theme-muted">
                            <span>Trace ID: <span className="font-mono font-semibold">{payload.traceId || 'N/A'}</span></span>
                            {payload.dagContext && (
                                <span className="font-mono">{payload.dagContext}</span>
                            )}
                        </div>
                    </div>
                )}
            </div>

            <div className="flex gap-2">
                <button 
                    onClick={() => onAction(ApprovalAction.APPROVE)}
                    className="btn btn-xs btn-success text-white"
                >
                    ✓ Authorize Escalation
                </button>
                <button 
                    onClick={() => onAction(ApprovalAction.REJECT)}
                    className="btn btn-xs btn-error text-white"
                >
                    ✕ Deny
                </button>
            </div>
        </div>
    </div>
  );
};
