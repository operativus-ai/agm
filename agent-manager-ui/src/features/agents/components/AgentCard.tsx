import React from 'react';
import type { AgentConfig } from '../../../shared/types/api';
import { Card } from '../../../shared/components/ui/Card';
import { Badge } from '../../../shared/components/ui/Badge';
import { Button } from '../../../shared/components/ui/Button';
import { Typography } from '../../../shared/components/ui/Typography';
import { useNavigate } from 'react-router-dom';
import { LuPlay, LuMessageCircle, LuPencil, LuTrash2, LuRotateCcw, LuHistory, LuCopy } from 'react-icons/lu';

interface AgentCardProps {
  agent: AgentConfig;
  onRunBackground?: (agent: AgentConfig) => void;
  onEdit?: (agent: AgentConfig) => void;
  onDelete?: (agent: AgentConfig) => void;
  onRestore?: (agent: AgentConfig) => void;
  onViewLogs?: (agent: AgentConfig) => void;
  onDuplicate?: (agent: AgentConfig) => void;
}

const RobotIcon = () => (
    <svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><rect width="18" height="12" x="3" y="6" rx="2"/><path d="M9 14v4"/><path d="M15 14v4"/><path d="M12 2v4"/><circle cx="9" cy="11" r="1"/><circle cx="15" cy="11" r="1"/></svg>
);

export const AgentCard: React.FC<AgentCardProps> = ({ 
  agent, 
  onRunBackground, 
  onEdit, 
  onDelete, 
  onRestore, 
  onViewLogs, 
  onDuplicate 
}) => {
  const navigate = useNavigate();
  const isInactive = agent.active === false;

  return (
    <Card className={`h-full flex flex-col hover:border-primary/50 transition-colors duration-300 ${isInactive ? 'opacity-60 saturate-50' : ''}`}>
      <Card.Body className="space-y-4 flex-1 flex flex-col">
        {/* Header Section */}
        <div className="flex items-start justify-between gap-2">
          <div className="flex items-center gap-3 min-w-0 flex-1">
             <div className="p-2 bg-obsidian-elevated/50 rounded-lg text-primary shrink-0">
                 <RobotIcon />
             </div>
             <div className="min-w-0 flex-1">
                <Typography.Heading level={4} className="leading-tight truncate" title={agent.name}>{agent.name}</Typography.Heading>
                <Typography.Text variant="small" className="text-muted-foreground truncate block" title={agent.model}>{agent.model}</Typography.Text>
             </div>
          </div>
          <div className="flex flex-col items-end gap-1 shrink-0">
            {agent.isReasoningEnabled && (
               <Badge variant="secondary" outline className="text-[10px] whitespace-nowrap">Reasoning</Badge>
            )}
            {isInactive && (
               <Badge variant="error" className="text-[10px] whitespace-nowrap">Inactive</Badge>
            )}
          </div>
        </div>
        
        {/* Body Section */}
        <div className="flex-1">
            <Typography.Text className="line-clamp-2 min-h-[3rem] text-sm break-words">
                {agent.description}
            </Typography.Text>
        </div>

        {/* Footer Section */}
        <div className="pt-4 mt-auto flex flex-col gap-3 border-t border-base-200 dark:border-base-800">
            {/* Status and ID Row */}
            <div className="flex items-center gap-2 w-full min-w-0">
                <div className={`w-2 h-2 rounded-full shrink-0 ${isInactive ? 'bg-error' : 'bg-success'}`} title={isInactive ? 'Inactive' : 'Active'} />
                <Typography.Text variant="small" className="font-mono opacity-50 text-[10px] truncate block" title={agent.agentId}>
                    {agent.agentId}
                </Typography.Text>
            </div>
            
            {/* Action Buttons Row */}
            <div className="flex flex-wrap items-center justify-between gap-y-3 gap-x-1 w-full">
                {/* Secondary Admin Tools */}
                <div className="flex flex-wrap items-center gap-1 bg-obsidian-elevated/50 dark:bg-base-800/50 rounded-lg p-0.5">
                    {onViewLogs && (
                        <Button 
                            size="sm" 
                            variant="ghost" 
                            title="View Logs & Memory"
                            onClick={() => onViewLogs(agent)}
                            className="px-2 shrink-0"
                        >
                            <LuHistory className="text-muted-foreground hover:text-primary transition-colors" />
                        </Button>
                    )}
                    {onDuplicate && (
                        <Button 
                            size="sm" 
                            variant="ghost" 
                            title="Duplicate Agent"
                            onClick={() => onDuplicate(agent)}
                            className="px-2 shrink-0"
                        >
                            <LuCopy className="text-muted-foreground hover:text-primary transition-colors" />
                        </Button>
                    )}
                    {onEdit && (
                        <Button 
                            size="sm" 
                            variant="ghost" 
                            title="Edit Configuration"
                            onClick={() => onEdit(agent)}
                            className="px-2 shrink-0"
                        >
                            <LuPencil className="text-muted-foreground hover:text-primary transition-colors" />
                        </Button>
                    )}
                    {onDelete && !isInactive && (
                        <Button 
                            size="sm" 
                            variant="ghost" 
                            title="Deactivate Agent"
                            className="px-2 shrink-0 text-error hover:bg-error/10 hover:text-error"
                            onClick={() => onDelete(agent)}
                        >
                            <LuTrash2 />
                        </Button>
                    )}
                    {onRestore && isInactive && (
                        <Button 
                            size="sm" 
                            variant="ghost" 
                            title="Restore Agent"
                            className="px-2 shrink-0 text-success hover:bg-success/10 hover:text-success"
                            onClick={() => onRestore(agent)}
                        >
                            <LuRotateCcw />
                        </Button>
                    )}
                </div>

                {/* Primary Action Tools */}
                <div className="flex flex-wrap items-center gap-2 ml-auto">
                    {onRunBackground && (
                        <Button 
                            size="sm" 
                            variant="outline" 
                            onClick={() => onRunBackground(agent)}
                            className="shrink-0 text-xs sm:text-sm"
                        >
                            <LuPlay className="mr-1.5" />
                            Run Task
                        </Button>
                    )}
                    <Button 
                        size="sm" 
                        variant="primary" 
                        onClick={() => navigate(`/chat/${agent.agentId}`)}
                        className="shrink-0 text-xs sm:text-sm"
                    >
                        <LuMessageCircle className="mr-1.5" />
                        Chat
                    </Button>
                </div>
            </div>
        </div>
      </Card.Body>
    </Card>
  );
};
