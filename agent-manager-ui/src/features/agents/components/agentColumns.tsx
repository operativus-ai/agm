import type { ColumnDef } from '@tanstack/react-table';
import type { AgentConfig } from '../../../shared/types/api';
import { Badge } from '../../../shared/components/ui/Badge';
import { LuBot, LuUsers } from 'react-icons/lu';
import { AgentActionsCell } from './AgentActionsCell';

interface AgentColumnHandlers {
  onRunBackground: (agent: AgentConfig) => void;
  onEdit: (agent: AgentConfig) => void;
  onDelete: (agent: AgentConfig) => void;
  onRestore: (agent: AgentConfig) => void;
  onViewCard: (agent: AgentConfig) => void;
  onChat: (agentId: string) => void;
}

/**
 * Column factory for the agents registry DataTable. Extracted from AgentsPage to
 * keep the page a thin assembler. Behavior-preserving: same cells, row actions
 * wired in via the handlers below.
 */
export function createAgentColumns(handlers: AgentColumnHandlers): ColumnDef<AgentConfig, unknown>[] {
  return [
    {
      accessorKey: 'name',
      header: 'Agent',
      cell: ({ row }) => {
        const agent = row.original;
        const isInactive = agent.active === false;
        return (
          <div className="flex items-center gap-3 min-w-0">
            <div className={`p-1.5 rounded-md shrink-0 ${isInactive ? 'bg-error/10 text-error' : 'bg-primary/10 text-primary'}`}>
              {agent.isTeam ? <LuUsers className="w-4 h-4" /> : <LuBot className="w-4 h-4" />}
            </div>
            <div className="min-w-0">
              <div className="font-medium truncate text-(--theme-foreground)" title={agent.name}>{agent.name}</div>
              <div className="text-xs text-(--theme-muted) truncate" title={agent.description}>{agent.description}</div>
            </div>
          </div>
        );
      },
    },
    {
      accessorKey: 'agentId',
      header: 'ID',
      cell: ({ getValue }) => (
        <span className="font-mono text-xs text-(--theme-muted) opacity-70 truncate block max-w-45" title={getValue() as string}>
          {getValue() as string}
        </span>
      ),
    },
    {
      accessorKey: 'model',
      header: 'Model',
      cell: ({ getValue }) => (
        <Badge variant="ghost" className="text-xs font-mono whitespace-nowrap">{getValue() as string}</Badge>
      ),
    },
    {
      accessorKey: 'active',
      header: 'Status',
      cell: ({ getValue }) => {
        const active = getValue() !== false;
        return (
          <div className="flex items-center gap-2">
            <div className={`w-2 h-2 rounded-full shrink-0 ${active ? 'bg-success' : 'bg-error'}`} />
            <span className={`text-xs font-medium ${active ? 'text-success' : 'text-error'}`}>
              {active ? 'Active' : 'Inactive'}
            </span>
          </div>
        );
      },
    },
    {
      id: 'tools',
      header: 'Tools',
      accessorFn: (row) => row.tools?.length ?? 0,
      cell: ({ getValue }) => (
        <span className="text-xs text-(--theme-muted)">{getValue() as number}</span>
      ),
    },
    {
      id: 'actions',
      header: '',
      enableSorting: false,
      cell: ({ row }) => (
        <AgentActionsCell
          agent={row.original}
          onRunBackground={handlers.onRunBackground}
          onEdit={handlers.onEdit}
          onDelete={handlers.onDelete}
          onRestore={handlers.onRestore}
          onViewCard={handlers.onViewCard}
          onChat={handlers.onChat}
        />
      ),
    },
  ];
}
