import React, { useMemo } from 'react';
import type { ColumnDef } from '@tanstack/react-table';
import { LuPencil, LuTrash2, LuPlus } from 'react-icons/lu';
import { DataTable } from '../../../shared/components/ui/DataTable';
import { Badge } from '../../../shared/components/ui/Badge';
import { Button } from '../../../shared/components/ui/Button';
import type { ComposioActionConfigResponse } from '../types';
import { TIER_LABELS } from '../types';

interface ActionListTableProps {
  actions: ComposioActionConfigResponse[];
  loading: boolean;
  onEdit: (action: ComposioActionConfigResponse) => void;
  onDelete: (action: ComposioActionConfigResponse) => void;
  onAdd: () => void;
}

const tierVariant = (tier: number): 'success' | 'warning' | 'error' => {
  if (tier === 1) return 'success';
  if (tier === 2) return 'warning';
  return 'error';
};

export const ActionListTable: React.FC<ActionListTableProps> = ({
  actions,
  loading,
  onEdit,
  onDelete,
  onAdd,
}) => {
  const columns = useMemo<ColumnDef<ComposioActionConfigResponse, unknown>[]>(() => [
    {
      accessorKey: 'actionName',
      header: 'Action Name',
      cell: ({ getValue }) => (
        <span className="font-mono text-sm text-primary font-bold">{getValue() as string}</span>
      ),
    },
    {
      accessorKey: 'llmToolName',
      header: 'LLM Tool Name',
      cell: ({ getValue }) => (
        <span className="font-mono text-xs text-(--theme-muted)">{getValue() as string}</span>
      ),
    },
    {
      accessorKey: 'tier',
      header: 'Tier',
      cell: ({ getValue }) => {
        const tier = getValue() as number;
        return (
          <Badge variant={tierVariant(tier)} outline>
            T{tier} — {TIER_LABELS[tier]}
          </Badge>
        );
      },
    },
    {
      accessorKey: 'enabled',
      header: 'Status',
      cell: ({ getValue }) => (
        <Badge variant={getValue() ? 'success' : 'neutral'} outline>
          {getValue() ? 'Enabled' : 'Disabled'}
        </Badge>
      ),
    },
    {
      accessorKey: 'updatedBy',
      header: 'Last Updated By',
      cell: ({ getValue }) => (
        <span className="text-xs text-(--theme-muted)">{getValue() as string}</span>
      ),
    },
    {
      id: 'actions',
      header: '',
      enableSorting: false,
      cell: ({ row }) => (
        <div className="flex items-center gap-2 justify-end">
          <Button variant="ghost" size="sm" onClick={() => onEdit(row.original)}>
            <LuPencil className="w-3.5 h-3.5" />
          </Button>
          <Button
            variant="ghost"
            size="sm"
            className="text-error hover:bg-error/10"
            onClick={() => onDelete(row.original)}
          >
            <LuTrash2 className="w-3.5 h-3.5" />
          </Button>
        </div>
      ),
    },
  ], [onEdit, onDelete]);

  return (
    <div className="space-y-3">
      <div className="flex justify-end">
        <Button size="sm" onClick={onAdd} className="gap-1.5">
          <LuPlus className="w-3.5 h-3.5" /> Add Action
        </Button>
      </div>
      <DataTable
        columns={columns}
        data={actions}
        enablePagination
        defaultPageSize={25}
        compact
        emptyMessage={loading ? 'Loading…' : 'No action configs found.'}
      />
    </div>
  );
};
