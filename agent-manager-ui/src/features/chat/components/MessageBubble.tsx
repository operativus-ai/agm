import React, { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import type { ChatMessage } from '../types';
import { ChatApi } from '../api/chat-api';
import { MessageRole, ApprovalAction, FeedbackDirection, RequiredActionType } from '../../../shared/types/enums';
import { MarkdownRenderer } from '../../../shared/components/ui/MarkdownRenderer';
import { observabilityApi } from '../../observability/api/observabilityApi';
import { EscalationApprovalCard } from './EscalationApprovalCard';
import { ToolApprovalCard } from './ToolApprovalCard';
import { UsageSummaryBadge } from './UsageSummary';

const BrainIcon = () => (
  <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M12 5a3 3 0 1 0-5.997.125 4 4 0 0 0-2.526 5.77 4 4 0 0 0 .556 6.588A4 4 0 1 0 12 18Z"/><path d="M12 5a3 3 0 1 1 5.997.125 4 4 0 0 1 2.526 5.77 4 4 0 0 1-.556 6.588A4 4 0 1 1 12 18Z"/><path d="M15 13a4.5 4.5 0 0 1-3-4 4.5 4.5 0 0 1-3 4"/></svg>
);

const UserIcon = () => (
    <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>
);

const BotIcon = () => (
    <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M12 8V4H8"/><rect width="16" height="12" x="4" y="8" rx="2"/><path d="M2 14h2"/><path d="M20 14h2"/><path d="M15 13v2"/><path d="M9 13v2"/></svg>
);

interface MessageBubbleProps {
  message: ChatMessage;
  agentId: string; // Required for continueRun
  runId?: string; 
  isPaused?: boolean;
  isGenerating?: boolean;
  onFollowUpClick?: (text: string) => void;
}

export const MessageBubble: React.FC<MessageBubbleProps> = ({ message, agentId, runId, isPaused, isGenerating, onFollowUpClick }) => {
  const isUser = message.role === MessageRole.USER;
  const [showThoughts, setShowThoughts] = useState(false);
  const [approvalStatus, setApprovalStatus] = useState<'pending' | 'approved' | 'rejected'>('pending');

  const [feedbackState, setFeedbackState] = useState<FeedbackDirection>(FeedbackDirection.NONE);
  const [showFeedbackModal, setShowFeedbackModal] = useState(false);
  const [feedbackCategory, setFeedbackCategory] = useState('');

  const submitFeedbackMutation = useMutation({
    mutationFn: (args: { direction: Exclude<FeedbackDirection, 'NONE'>, category?: string }) => {
      return observabilityApi.submitAgentFeedback({
        messageId: message.id,
        agentId: agentId,
        runId: runId,
        rating: args.direction === FeedbackDirection.THUMBS_UP ? 1 : -1,
        category: args.direction === FeedbackDirection.THUMBS_DOWN ? args.category : undefined,
        metadata: {}
      });
    },
    onMutate: (args) => {
      setFeedbackState(args.direction);
      setShowFeedbackModal(false);
    },
    onError: (err) => {
      console.error("Failed to submit feedback", err);
      setFeedbackState(FeedbackDirection.NONE); // Rollback optimistic update
    }
  });

  const handleFeedback = (direction: Exclude<FeedbackDirection, 'NONE'>) => {
      submitFeedbackMutation.mutate({ direction, category: feedbackCategory });
  };

  const handleAction = async (action: ApprovalAction) => {
      const required = message.metadata?.required_action;
      if (!required) {
          console.error("HITL action invoked without required_action payload");
          return;
      }
      const decision = action === ApprovalAction.APPROVE ? 'APPROVED' : 'REJECTED';
      try {
          if (required.type === RequiredActionType.TOOL_APPROVAL) {
              if (!required.approvalId) throw new Error("TOOL_APPROVAL payload missing approvalId");
              await ChatApi.resolveToolApproval(required.approvalId, decision);
          } else if (required.type === RequiredActionType.SWARM_ESCALATION_APPROVAL) {
              if (!required.escalationId) throw new Error("SWARM_ESCALATION_APPROVAL payload missing escalationId");
              await ChatApi.resolveEscalation(required.escalationId, decision);
          } else {
              throw new Error(`Unsupported required_action.type: ${required.type}`);
          }
          setApprovalStatus(action === ApprovalAction.APPROVE ? 'approved' : 'rejected');
      } catch (error) {
          console.error("Failed to resolve HITL action:", error);
          alert("Failed to process action");
      }
  };

  const isToolApprovalRequired = message.metadata?.required_action?.type === RequiredActionType.TOOL_APPROVAL;
  const isEscalationRequired = message.metadata?.required_action?.type === RequiredActionType.SWARM_ESCALATION_APPROVAL;
  const isApprovalRequired = isPaused && message.role === MessageRole.ASSISTANT && (isToolApprovalRequired || isEscalationRequired || message.content.includes("Tool execution paused"));

  return (
    <div className={`flex w-full mb-6 group ${isUser ? 'justify-end' : 'justify-start'}`}>
      <div className={`flex max-w-[80%] md:max-w-[70%] gap-3 ${isUser ? 'flex-row-reverse' : 'flex-row'}`}>
        
        {/* Avatar */}
        <div className={`
          shrink-0 w-8 h-8 rounded-full flex items-center justify-center
          ${isUser ? 'bg-agent-blue text-white' : 'bg-obsidian-elevated text-theme-muted'}
        `}>
          {isUser ? <UserIcon /> : <BotIcon />}
        </div>

        {/* Content */}
        <div className={`flex flex-col gap-2 ${isUser ? 'items-end' : 'items-start'}`}>
            
          {/* Metadata Name */}
          <span className="text-xs opacity-50 px-1">
            {isUser ? 'You' : 'Agent'}
          </span>

          {/* Bubble */}
          <div className={`
            p-4 rounded-2xl shadow-sm text-sm leading-relaxed whitespace-pre-wrap
            ${isUser
              ? 'bg-agent-blue text-white rounded-tr-none'
              : 'bg-obsidian-elevated border border-obsidian-stroke/50 text-theme-foreground rounded-tl-none'}
            ${isApprovalRequired ? 'border-l-4 border-l-warn-amber' : ''}
          `}>
            {/* Tool Calls (Sandbox/RAG) */}
            {message.toolCalls && message.toolCalls.length > 0 && (
                <div className="flex flex-col gap-2 mb-3 mt-1">
                    {message.toolCalls.map((tool, idx) => (
                        <div key={idx} className="bg-obsidian-surface rounded-lg overflow-hidden border border-obsidian-stroke/50 text-xs">
                            <div className="bg-obsidian-elevated px-3 py-1 font-mono text-xs opacity-70 flex justify-between">
                                <span>$ {tool.name}</span>
                                <span className="text-[10px] uppercase tracking-wider opacity-50">Tool</span>
                            </div>
                            <div className="p-3 font-mono overflow-x-auto whitespace-pre">
                                {tool.name === 'run_python' ? (
                                    // Special formatting for Python code
                                    <code className="block text-agent-blue">{tool.arguments}</code>
                                ) : (
                                    <span className="opacity-80">{tool.arguments}</span>
                                )}
                            </div>
                        </div>
                    ))}
                </div>
            )}

            {isUser ? (
                message.content
            ) : (
                <div className="w-full overflow-x-auto">
                    <MarkdownRenderer content={message.content || ''} />
                </div>
            )}
            {/* Loading Indicator */}
            {isGenerating && (
                <span className="inline-block ml-1">
                    <span className="loading loading-dots loading-xs opacity-70 align-middle"></span>
                </span>
            )}

            {/* HITL Action Card */}
            {isApprovalRequired && isToolApprovalRequired && approvalStatus === 'pending' && message.metadata?.required_action && (
                <ToolApprovalCard 
                    payload={message.metadata.required_action}
                    onAction={handleAction}
                />
            )}
            
             {/* Post-Action Status */}
             {isApprovalRequired && approvalStatus !== 'pending' && (
                <div className={`mt-2 text-xs font-bold ${approvalStatus === 'approved' ? 'text-success' : 'text-error'}`}>
                    {approvalStatus === 'approved' ? '✓ Approved' : '✕ Rejected'}
                </div>
            )}

            {/* Swarm Escalation HITL Card (G-05) */}
            {message.metadata?.required_action?.type === RequiredActionType.SWARM_ESCALATION_APPROVAL && approvalStatus === 'pending' && message.metadata?.required_action && (
                <EscalationApprovalCard 
                    payload={message.metadata.required_action} 
                    onAction={handleAction} 
                />
            )}

            {/* Follow-up Suggestions */}
            {!isUser && message.followUpSuggestions && message.followUpSuggestions.length > 0 && !isGenerating && (
                <div className="mt-4 flex flex-wrap gap-2 pt-2 border-t border-obsidian-stroke/30">
                    {message.followUpSuggestions.map((suggestion, idx) => (
                        <button
                            key={idx}
                            onClick={() => onFollowUpClick?.(suggestion)}
                            className="btn btn-xs btn-outline btn-primary rounded-full px-3 animate-in fade-in slide-in-from-bottom-2 whitespace-normal h-auto text-left"
                        >
                            {suggestion}
                        </button>
                    ))}
                </div>
            )}
          </div>

          {/* Thoughts (Agent Only) */}
          {!isUser && message.thoughts && (
            <div className="w-full max-w-full">
              <button 
                onClick={() => setShowThoughts(!showThoughts)}
                className="flex items-center gap-2 text-xs text-info hover:text-info-focus transition-colors mb-1"
              >
                <BrainIcon />
                <span>{showThoughts ? 'Hide' : 'Show'} Reasoning Process</span>
              </button>
              
              {showThoughts && (
                <div className="bg-obsidian-surface/50 p-3 rounded-lg border border-obsidian-stroke/50 text-xs font-mono text-theme-muted animate-in fade-in slide-in-from-top-2">
                   {message.thoughts}
                </div>
              )}
            </div>
          )}
          
          {/* Timestamp & Execution Time & Feedback Hooks */}
          <div className="flex w-full justify-between items-center mt-1">
            <div className="flex gap-2 items-center px-1 text-[10px] opacity-40">
              <span>{new Date(message.timestamp).toLocaleTimeString()}</span>
              {!isUser && message.executionTimeMs && (
                  <span>• {(message.executionTimeMs / 1000).toFixed(1)}s</span>
              )}
              {!isUser && <UsageSummaryBadge usage={message.usage} />}
            </div>

            {/* Continuous Learning Loop Feedback */}
            {!isUser && !isGenerating && (
                <div className="flex gap-1 items-center opacity-0 group-hover:opacity-100 transition-opacity">
                    <button 
                        onClick={() => handleFeedback(FeedbackDirection.THUMBS_UP)}
                        className={`btn btn-xs btn-ghost btn-circle ${feedbackState === FeedbackDirection.THUMBS_UP ? 'text-success' : 'opacity-60 hover:opacity-100'}`}
                    >
                        👍
                    </button>
                    <button 
                        onClick={() => setShowFeedbackModal(true)}
                        className={`btn btn-xs btn-ghost btn-circle ${feedbackState === FeedbackDirection.THUMBS_DOWN ? 'text-error' : 'opacity-60 hover:opacity-100'}`}
                    >
                        👎
                    </button>
                </div>
            )}
          </div>

          {/* Feedback Capture Form */}
          {showFeedbackModal && (
              <div className="w-full mt-2 p-3 bg-obsidian-surface border border-obsidian-stroke/50 rounded-lg text-sm animate-in fade-in slide-in-from-top-2">
                  <p className="font-bold mb-2">What went wrong?</p>
                  <select 
                      className="select select-bordered select-sm w-full mb-2"
                      value={feedbackCategory}
                      onChange={(e) => setFeedbackCategory(e.target.value)}
                  >
                      <option disabled value="">Select reason...</option>
                      <option value="HALLUCINATION">Factually Incorrect (Hallucination)</option>
                      <option value="POOR_RAG">Missed context / Poor DB search</option>
                      <option value="TOOL_ERROR">Failed to use tool properly</option>
                      <option value="FORMATTING">Formatting / Style issue</option>
                  </select>
                  <div className="flex justify-end gap-2 mt-2">
                      <button className="btn btn-xs btn-ghost" onClick={() => setShowFeedbackModal(false)}>Cancel</button>
                      <button className="btn btn-xs btn-primary" onClick={() => handleFeedback(FeedbackDirection.THUMBS_DOWN)} disabled={!feedbackCategory}>Submit</button>
                  </div>
              </div>
          )}

        </div>
      </div>
    </div>
  );
};
