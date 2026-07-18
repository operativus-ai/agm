import React, { useState } from 'react';
import { useAgentCard } from '../hooks/useA2a';
import { Button } from '../../../shared/components/ui/Button';
import { Typography } from '../../../shared/components/ui/Typography';
import { useEscapeToClose } from '../../../shared/hooks/useEscapeToClose';
import { LuCopy, LuCheck } from 'react-icons/lu';

interface AgentCardViewerProps {
  agentId: string;
  agentName: string;
  isOpen: boolean;
  onClose: () => void;
}

export const AgentCardViewer: React.FC<AgentCardViewerProps> = ({ agentId, agentName, isOpen, onClose }) => {
  const { data: card, isLoading, isError, error } = useAgentCard(isOpen ? agentId : null);
  const [copied, setCopied] = useState(false);

  useEscapeToClose(onClose, isOpen);

  if (!isOpen) return null;

  const jsonString = card ? JSON.stringify(card, null, 2) : '';

  const handleCopy = async () => {
    if (!jsonString) return;
    await navigator.clipboard.writeText(jsonString);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <dialog className="modal modal-open">
      <div className="modal-box w-11/12 max-w-2xl bg-obsidian-raised border border-obsidian-stroke/50 max-h-[80vh] flex flex-col">
        <div className="flex items-center justify-between mb-4">
          <div>
            <h3 className="font-bold text-lg text-theme-foreground">A2A Agent Card</h3>
            <Typography.Text variant="muted" className="text-sm">{agentName}</Typography.Text>
          </div>
          <Button
            variant="ghost"
            size="sm"
            onClick={handleCopy}
            disabled={!card}
            className="gap-1.5"
          >
            {copied ? <LuCheck className="w-4 h-4 text-active-green" /> : <LuCopy className="w-4 h-4" />}
            {copied ? 'Copied' : 'Copy JSON'}
          </Button>
        </div>

        <div className="flex-1 overflow-auto rounded-md bg-obsidian-base border border-obsidian-stroke/30 p-4">
          {isLoading && (
            <div className="flex items-center justify-center h-32">
              <span className="loading loading-spinner loading-md text-agent-blue" />
            </div>
          )}
          {isError && (
            <div className="p-3 bg-error-red/10 text-error-red rounded-md text-sm border border-error-red/20">
              {(error as Error)?.message || 'Failed to load agent card.'}
            </div>
          )}
          {card && (
            <pre className="text-sm font-mono text-theme-foreground whitespace-pre-wrap break-words">
              {jsonString}
            </pre>
          )}
        </div>

        <div className="flex justify-end pt-4 mt-4 border-t border-obsidian-stroke/30">
          <Button variant="ghost" onClick={onClose}>Close</Button>
        </div>
      </div>
      <form method="dialog" className="modal-backdrop">
        <button onClick={onClose}>close</button>
      </form>
    </dialog>
  );
};
