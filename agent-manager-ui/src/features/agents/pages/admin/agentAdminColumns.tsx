import type { ColumnDef } from '@tanstack/react-table';
import type { AgentConfig } from '../../../../shared/types/api';
import { Badge } from '../../../../shared/components/ui/Badge';
import { Button } from '../../../../shared/components/ui/Button';
import { LuPencil, LuBookOpen, LuLock, LuLockOpen } from 'react-icons/lu';

/**
 * Column definitions for the Agent Administration table, extracted from
 * AgentAdminDashboardPage to keep the page a thin assembler. Pure factory: it
 * takes the page's row-action handlers and returns the
 * TanStack column array. No data fetching or local state here.
 */
export interface AgentAdminColumnHandlers {
  onEdit: (agent: AgentConfig) => void;
  onLoadKnowledge: (agentId: string) => void;
  onQuarantine: (agentId: string, name: string) => void;
  onUnquarantine: (agentId: string, name: string) => void;
}

export function buildAgentAdminColumns({
  onEdit,
  onLoadKnowledge,
  onQuarantine,
  onUnquarantine,
}: AgentAdminColumnHandlers): ColumnDef<AgentConfig, unknown>[] {
  return [
    {
      accessorKey: 'agentId',
      header: 'Agent ID',
      cell: ({ getValue }) => (
        <span className="font-mono text-xs text-(--theme-muted) opacity-70 truncate block max-w-45" title={getValue() as string}>
          {getValue() as string}
        </span>
      ),
    },
    {
      accessorKey: 'name',
      header: 'Name',
      cell: ({ getValue }) => (
        <span className="font-medium text-sm text-(--theme-foreground)">{getValue() as string}</span>
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
      id: 'actions',
      header: '',
      enableSorting: false,
      cell: ({ row }) => {
        const agent = row.original;
        return (
          <div className="flex items-center justify-end gap-1">
            <Button
              size="sm"
              variant="ghost"
              title="Edit"
              className="px-2 text-(--theme-muted) hover:text-primary"
              onClick={() => onEdit(agent)}
            >
              <LuPencil className="w-3.5 h-3.5" />
            </Button>
            <button
              type="button"
              onClick={() => onLoadKnowledge(agent.agentId)}
              aria-label="Load knowledge base"
              title="Load KB"
              className="btn btn-ghost btn-sm btn-square rounded-md text-(--theme-muted) hover:text-info"
            >
              <LuBookOpen className="w-4 h-4" />
            </button>
            <button
              type="button"
              onClick={() => onQuarantine(agent.agentId, agent.name)}
              aria-label="Quarantine agent"
              title="Quarantine"
              className="btn btn-ghost btn-sm btn-square rounded-md text-(--theme-muted) hover:text-warning"
            >
              <LuLock className="w-4 h-4" />
            </button>
            <button
              type="button"
              onClick={() => onUnquarantine(agent.agentId, agent.name)}
              aria-label="Unquarantine agent"
              title="Unquarantine"
              className="btn btn-ghost btn-sm btn-square rounded-md text-(--theme-muted) hover:text-success"
            >
              <LuLockOpen className="w-4 h-4" />
            </button>
          </div>
        );
      },
    },
  ];
}
