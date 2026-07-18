import React from 'react';
import { LuPencil, LuTrash2, LuPlus } from 'react-icons/lu';
import { Button } from '../../../shared/components/ui/Button';
import { Badge } from '../../../shared/components/ui/Badge';
import type { ComposioConnectionConfigResponse } from '../types';

interface ConnectionListTableProps {
  connection: ComposioConnectionConfigResponse | null;
  loading: boolean;
  onEdit: (conn: ComposioConnectionConfigResponse | null) => void;
  onDelete: (conn: ComposioConnectionConfigResponse) => void;
}

export const ConnectionListTable: React.FC<ConnectionListTableProps> = ({
  connection,
  loading,
  onEdit,
  onDelete,
}) => {
  if (loading) {
    return <p className="text-sm text-(--theme-muted) italic">Loading…</p>;
  }

  if (!connection) {
    return (
      <div className="space-y-3">
        <p className="text-sm text-(--theme-muted) italic">No connection configured for this org.</p>
        <Button size="sm" onClick={() => onEdit(null)} className="gap-1.5">
          <LuPlus className="w-3.5 h-3.5" /> Set Connection
        </Button>
      </div>
    );
  }

  return (
    <div className="bg-(--theme-card) rounded-xl border border-(--theme-muted)/10 p-5 space-y-4">
      <div className="flex items-start justify-between">
        <div className="space-y-1">
          <p className="text-xs uppercase font-bold tracking-wider text-(--theme-muted)">Connection ID</p>
          <p className="font-mono text-sm text-primary font-bold">{connection.connectionId}</p>
        </div>
        <Badge variant="success" outline>Active</Badge>
      </div>

      <div className="grid grid-cols-2 gap-4 text-xs text-(--theme-muted)">
        <div>
          <p className="uppercase font-bold tracking-wider mb-0.5">Org ID</p>
          <p className="font-mono">{connection.orgId}</p>
        </div>
        <div>
          <p className="uppercase font-bold tracking-wider mb-0.5">Last Updated</p>
          <p>{new Date(connection.updatedAt).toLocaleString()}</p>
        </div>
      </div>

      <div className="flex gap-2 pt-2 border-t border-(--theme-muted)/10">
        <Button variant="outline" size="sm" onClick={() => onEdit(connection)} className="gap-1.5">
          <LuPencil className="w-3.5 h-3.5" /> Update
        </Button>
        <Button
          variant="outline"
          size="sm"
          className="text-error border-error/30 hover:bg-error/10 gap-1.5"
          onClick={() => onDelete(connection)}
        >
          <LuTrash2 className="w-3.5 h-3.5" /> Remove
        </Button>
      </div>
    </div>
  );
};
