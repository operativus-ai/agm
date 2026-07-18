import React, { useState } from 'react';
import type { AgentConfig } from '../../../shared/types/api';
import { AgentsApi } from '../api/agents-api';
import { useBackgroundRunStore } from '../store/backgroundRunStore';
import { Button } from '../../../shared/components/ui/Button';
import { Typography } from '../../../shared/components/ui/Typography';
import { useEscapeToClose } from '../../../shared/hooks/useEscapeToClose';
import { LuPlay } from 'react-icons/lu';
import { RunStatus } from '../../../shared/types/enums';

interface RunBackgroundModalProps {
  agent: AgentConfig;
  isOpen: boolean;
  onClose: () => void;
}

export const RunBackgroundModal: React.FC<RunBackgroundModalProps> = ({ agent, isOpen, onClose }) => {
  const [prompt, setPrompt] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const { addRun } = useBackgroundRunStore();

  useEscapeToClose(onClose, isOpen);

  if (!isOpen) return null;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!prompt.trim()) return;

    try {
      setLoading(true);
      setError(null);
      
      const response = await AgentsApi.runBackground(agent.agentId, { input: prompt });
      
      addRun({
          runId: response.runId || `run-${Date.now()}`,
          agentId: agent.agentId,
          agentName: agent.name,
          status: RunStatus.PENDING,
          startedAt: Date.now()
      });
      
      setPrompt('');
      onClose();
    } catch (err: any) {
      setError(err.message || 'Failed to start background run');
    } finally {
      setLoading(false);
    }
  };

  return (
    <dialog className="modal modal-open">
      <div className="modal-box w-11/12 max-w-2xl">
        <h3 className="font-bold text-lg mb-4">Run {agent.name} in Background</h3>
        <form onSubmit={handleSubmit} className="space-y-4">
           <Typography.Text variant="muted">
              Dispatch a long-running, asynchronous task to the agent.
           </Typography.Text>

           <div className="form-control">
              <label className="label">
                 <span className="label-text">Task Prompt</span>
              </label>
              <textarea 
                  className="textarea textarea-bordered w-full h-32" 
                  placeholder="Describe the task you want the agent to perform..."
                  value={prompt}
                  onChange={(e) => setPrompt(e.target.value)}
                  disabled={loading}
                  required
              />
           </div>

           {error && (
               <div className="p-3 bg-error/10 text-error rounded-md text-sm border border-error/20">
                   {error}
               </div>
           )}

           <div className="flex justify-end gap-2 pt-4 border-t border-base-200 dark:border-base-800">
               <Button type="button" variant="ghost" onClick={onClose} disabled={loading}>
                   Cancel
               </Button>
               <Button type="submit" variant="primary" disabled={loading || !prompt.trim()}>
                   {loading ? <span className="loading loading-spinner loading-sm" /> : <LuPlay className="mr-2" />}
                   Dispatch Task
               </Button>
           </div>
        </form>
      </div>
      <form method="dialog" className="modal-backdrop">
        <button onClick={onClose}>close</button>
      </form>
    </dialog>
  );
};

