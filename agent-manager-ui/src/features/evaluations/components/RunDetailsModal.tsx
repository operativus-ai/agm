import React, { useEffect, useState } from 'react';
import { evaluationApi } from '../../../shared/api/evaluationApi';
import { RunStatus } from '../../../shared/types/enums';
import { Typography } from '../../../shared/components/ui/Typography';
import { Button } from '../../../shared/components/ui/Button';
import { useEscapeToClose } from '../../../shared/hooks/useEscapeToClose';
import { LuStar } from 'react-icons/lu';

interface RunDetailsModalProps {
  suiteId: string;
  runId: string;
  onClose: () => void;
}

export const RunDetailsModal: React.FC<RunDetailsModalProps> = ({ suiteId, runId, onClose }) => {
  const [details, setDetails] = useState<any>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [rating, setRating] = useState(0);
  const [hoverRating, setHoverRating] = useState(0);
  const [comment, setComment] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [feedbackStatus, setFeedbackStatus] = useState<'idle' | 'sent' | 'error'>('idle');
  const [feedbackError, setFeedbackError] = useState<string | null>(null);

  useEscapeToClose(onClose);

  const submitFeedback = async () => {
    if (rating === 0) return;
    setSubmitting(true);
    setFeedbackError(null);
    try {
      await evaluationApi.submitFeedback({ runId, rating, comment: comment.trim() || undefined });
      setFeedbackStatus('sent');
    } catch (err) {
      setFeedbackStatus('error');
      setFeedbackError((err as Error).message || 'Failed to submit feedback.');
    } finally {
      setSubmitting(false);
    }
  };

  useEffect(() => {
    const fetchRunDetails = async () => {
      try {
        setLoading(true);
        const data = await evaluationApi.getRun(runId);
        setDetails(data);
      } catch (err: any) {
        setError(err.message || 'Failed to load run details');
      } finally {
        setLoading(false);
      }
    };
    fetchRunDetails();
  }, [suiteId, runId]);

  return (
    <dialog className="modal modal-open">
      <div className="modal-box w-11/12 max-w-4xl max-h-[90vh] flex flex-col">
        <h3 className="font-bold text-lg border-b border-base-200 pb-2 mb-4 shrink-0">
          Evaluation Run Details <span className="opacity-50 text-sm font-normal">({runId})</span>
        </h3>
        
        <div className="flex-1 overflow-y-auto min-h-0 relative">
            {loading && (
                <div className="absolute inset-0 flex items-center justify-center bg-base-100/50 backdrop-blur-sm z-10">
                    <span className="loading loading-spinner text-primary"></span>
                </div>
            )}
            
            {error && (
                <div className="p-4 bg-error/10 text-error rounded-box mb-4">
                    {error}
                </div>
            )}
            
            {!loading && !error && details && (
                <div className="space-y-6">
                   <div className="stats shadow w-full">
                       <div className="stat">
                           <div className="stat-title">Status</div>
                           <div className={`stat-value text-lg ${details.status === RunStatus.COMPLETED ? 'text-success' : details.status === RunStatus.FAILED ? 'text-error' : 'text-warning'}`}>
                               {details.status}
                           </div>
                       </div>
                       <div className="stat">
                           <div className="stat-title">Agent</div>
                           <div className="stat-value text-lg">{details.agentId}</div>
                       </div>
                       <div className="stat">
                           <div className="stat-title">Overall Score</div>
                           <div className="stat-value text-lg">
                                {details.metrics && details.metrics.aggregateScore !== undefined 
                                    ? details.metrics.aggregateScore.toFixed(2) 
                                    : 'N/A'}
                           </div>
                       </div>
                   </div>

                   <div>
                       <Typography.Heading level={4} className="mb-2">Result Data</Typography.Heading>
                       <div className="bg-obsidian-elevated rounded-box p-4 overflow-x-auto font-mono text-sm max-h-96">
                           <pre>{JSON.stringify(details, null, 2)}</pre>
                       </div>
                   </div>

                   <div className="border-t border-base-200 pt-4">
                       <Typography.Heading level={4} className="mb-2">Reviewer feedback</Typography.Heading>
                       {feedbackStatus === 'sent' ? (
                           <div className="text-sm text-success py-2">Feedback recorded — thanks.</div>
                       ) : (
                           <div className="space-y-3">
                               <div>
                                   <div className="text-xs text-(--theme-muted) mb-1">Rating</div>
                                   <div className="flex items-center gap-1">
                                       {[1, 2, 3, 4, 5].map(n => {
                                           const filled = (hoverRating || rating) >= n;
                                           return (
                                               <button
                                                   key={n}
                                                   type="button"
                                                   className="p-1 hover:scale-110 transition-transform"
                                                   onClick={() => setRating(n)}
                                                   onMouseEnter={() => setHoverRating(n)}
                                                   onMouseLeave={() => setHoverRating(0)}
                                                   aria-label={`Rate ${n} star${n === 1 ? '' : 's'}`}
                                               >
                                                   <LuStar
                                                       className={`w-5 h-5 ${filled ? 'fill-warning text-warning' : 'text-(--theme-muted)'}`}
                                                   />
                                               </button>
                                           );
                                       })}
                                       {rating > 0 && (
                                           <span className="ml-2 text-xs text-(--theme-muted)">{rating}/5</span>
                                       )}
                                   </div>
                               </div>
                               <div>
                                   <div className="text-xs text-(--theme-muted) mb-1">Comment (optional)</div>
                                   <textarea
                                       className="textarea textarea-bordered w-full text-sm bg-obsidian-surface border-obsidian-stroke"
                                       rows={2}
                                       placeholder="What went well or what should change?"
                                       value={comment}
                                       onChange={(e) => setComment(e.target.value)}
                                   />
                               </div>
                               {feedbackStatus === 'error' && feedbackError && (
                                   <div className="text-xs text-error">{feedbackError}</div>
                               )}
                               <div>
                                   <Button
                                       size="sm"
                                       onClick={submitFeedback}
                                       disabled={rating === 0 || submitting}
                                       className="gap-1.5"
                                   >
                                       {submitting && <span className="loading loading-spinner loading-xs" />}
                                       Submit feedback
                                   </Button>
                               </div>
                           </div>
                       )}
                   </div>
                </div>
            )}
        </div>

        <div className="modal-action shrink-0 border-t border-base-200 pt-4 mt-4">
          <Button onClick={onClose} variant="ghost">Close</Button>
        </div>
      </div>
      <form method="dialog" className="modal-backdrop">
        <button onClick={onClose}>close</button>
      </form>
    </dialog>
  );
};
