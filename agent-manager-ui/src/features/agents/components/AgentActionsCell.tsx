import React from 'react';
import type { AgentConfig } from '../../../shared/types/api';
import { Button } from '../../../shared/components/ui/Button';
import {
  LuPlay, LuMessageSquare, LuPencil, LuTrash2,
  LuUndoDot, LuFileJson,
} from 'react-icons/lu';

export interface ActionsCellProps {
  agent: AgentConfig;
  onRunBackground: (agent: AgentConfig) => void;
  onEdit: (agent: AgentConfig) => void;
  onDelete: (agent: AgentConfig) => void;
  onRestore: (agent: AgentConfig) => void;
  onViewCard: (agent: AgentConfig) => void;
  onChat: (agentId: string) => void;
}

/**
 * Row-actions cell for the agents table: Run / Chat buttons plus inline icon
 * actions (Edit / A2A Card / Deactivate / Restore). Extracted from AgentsPage.
 */
export const AgentActionsCell: React.FC<ActionsCellProps> = ({
  agent, onRunBackground, onEdit, onDelete, onRestore, onViewCard, onChat,
}) => {
  const isInactive = agent.active === false;

  return (
    <div className="flex items-center justify-end gap-1.5">
      <Button
        size="sm"
        variant="ghost"
        title="Run Task"
        className="px-2 text-(--theme-muted) hover:text-primary"
        onClick={() => onRunBackground(agent)}
      >
        <LuPlay className="w-3.5 h-3.5" />
      </Button>
      <Button
        size="sm"
        variant="primary"
        className="px-3 text-xs gap-1.5"
        onClick={() => onChat(agent.agentId)}
      >
        <LuMessageSquare className="w-3.5 h-3.5" /> Chat
      </Button>

      <button
        type="button"
        onClick={() => onEdit(agent)}
        aria-label="Edit agent"
        title="Edit"
        className="btn btn-ghost btn-sm btn-square rounded-md text-(--theme-muted) hover:text-(--theme-foreground)"
      >
        <LuPencil className="w-4 h-4" />
      </button>
      <button
        type="button"
        onClick={() => onViewCard(agent)}
        aria-label="View A2A card"
        title="A2A Card"
        className="btn btn-ghost btn-sm btn-square rounded-md text-(--theme-muted) hover:text-agent-blue"
      >
        <LuFileJson className="w-4 h-4" />
      </button>
      {!isInactive && (
        <button
          type="button"
          onClick={() => onDelete(agent)}
          aria-label="Deactivate agent"
          title="Deactivate"
          className="btn btn-ghost btn-sm btn-square rounded-md text-(--theme-muted) hover:text-error"
        >
          <LuTrash2 className="w-4 h-4" />
        </button>
      )}
      {isInactive && (
        <button
          type="button"
          onClick={() => onRestore(agent)}
          aria-label="Restore agent"
          title="Restore"
          className="btn btn-ghost btn-sm btn-square rounded-md text-(--theme-muted) hover:text-success"
        >
          <LuUndoDot className="w-4 h-4" />
        </button>
      )}
    </div>
  );
};
