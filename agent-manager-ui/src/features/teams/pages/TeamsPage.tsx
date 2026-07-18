import React, { useEffect, useMemo, useState } from 'react';
import { orchestrationApi } from '../../../shared/api/orchestrationApi';
import { logger } from '../../../utils/logger';
import type { Team } from '../../../shared/types/orchestration';
import type { ColumnDef } from '@tanstack/react-table';
import { PageHeader } from '../../../shared/components/ui/PageHeader';
import { Alert } from '../../../shared/components/ui/Alert';
import { Button } from '../../../shared/components/ui/Button';
import { Badge } from '../../../shared/components/ui/Badge';
import { DataTable } from '../../../shared/components/ui/DataTable';
import { Dialog } from '../../../shared/components/ui/Dialog';
import { useNavigate } from 'react-router-dom';
import {
    LuUsers, LuPlus, LuPencil, LuMessageSquare,
    LuEye, LuCopy, LuSearch, LuArchive, LuArchiveRestore,
    LuFileText, LuTrash2,
} from 'react-icons/lu';
import { PageContainer } from '../../../shared/components/ui/PageContainer';

export const TeamsPage: React.FC = () => {
  const [teams, setTeams] = useState<Team[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [feedback, setFeedback] = useState<{ type: 'success' | 'error'; message: string } | null>(null);
  const navigate = useNavigate();

  // Pagination state
  const PAGE_SIZE = 20;
  const [pageIndex, setPageIndex] = useState(0);
  const [totalElements, setTotalElements] = useState(0);

  // Search & filter state
  const [search, setSearch] = useState('');
  const [debouncedSearch, setDebouncedSearch] = useState('');
  const [showArchived, setShowArchived] = useState(false);

  // Delete/archive confirmation state
  const [archiveTarget, setArchiveTarget] = useState<Team | null>(null);
  const [archiveOpen, setArchiveOpen] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<Team | null>(null);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const [deleting, setDeleting] = useState(false);

  // Debounce search input
  useEffect(() => {
    const timer = setTimeout(() => {
      setDebouncedSearch(search);
      setPageIndex(0); // reset to first page on search
    }, 300);
    return () => clearTimeout(timer);
  }, [search]);

  useEffect(() => {
    loadTeams();
  }, [pageIndex, debouncedSearch, showArchived]);

  const loadTeams = async () => {
    try {
      setLoading(true);
      setError(null);
      const data = await orchestrationApi.getTeams({
        page: pageIndex,
        size: PAGE_SIZE,
        search: debouncedSearch || undefined,
        showArchived,
      });
      if (data && data.content) {
        setTeams(data.content);
        setTotalElements(data.page.totalElements);
      } else {
        setTeams([]);
        setTotalElements(0);
      }
    } catch (err: any) {
      logger.error("API Fetch failed", err);
      setError(err.message || 'Failed to load teams.');
    } finally {
      setLoading(false);
    }
  };

  const handleArchiveClick = (team: Team) => {
    setArchiveTarget(team);
    setArchiveOpen(true);
  };

  const handleArchiveConfirm = async () => {
    if (!archiveTarget) return;
    try {
      setFeedback(null);
      if (archiveTarget.archived) {
        await orchestrationApi.restoreTeam(archiveTarget.id);
        setFeedback({ type: 'success', message: `"${archiveTarget.name}" restored.` });
      } else {
        await orchestrationApi.archiveTeam(archiveTarget.id);
        setFeedback({ type: 'success', message: `"${archiveTarget.name}" archived.` });
      }
      setArchiveTarget(null);
      setArchiveOpen(false);
      loadTeams();
    } catch (err: any) {
      setFeedback({ type: 'error', message: err.message || 'Operation failed.' });
      setArchiveOpen(false);
    }
  };

  const handleDeleteConfirm = async () => {
    if (!deleteTarget) return;
    setDeleting(true);
    setDeleteError(null);
    try {
      await orchestrationApi.deleteTeam(deleteTarget.id);
      setFeedback({ type: 'success', message: `"${deleteTarget.name}" permanently deleted.` });
      setDeleteTarget(null);
      loadTeams();
    } catch (err: any) {
      setDeleteError(err?.message || 'Delete failed.');
    } finally {
      setDeleting(false);
    }
  };

  const handleClone = async (team: Team) => {
    try {
      setFeedback(null);
      const cloned = await orchestrationApi.cloneTeam(team.id);
      setFeedback({ type: 'success', message: `"${team.name}" cloned successfully.` });
      navigate(`/teams/${cloned.id}`);
    } catch (err: any) {
      setFeedback({ type: 'error', message: `Failed to clone "${team.name}".` });
    }
  };

  // ── Column Definitions ──────────────────────────────────────
  const columns = useMemo<ColumnDef<Team, unknown>[]>(() => [
    {
      accessorKey: 'name',
      header: 'Team Name',
      cell: ({ row }) => {
        const team = row.original;
        return (
          <div
            className="flex items-center gap-3 min-w-0 cursor-pointer hover:text-primary transition-colors"
            onClick={() => navigate(`/teams/${team.id}`)}
          >
            <LuUsers className="w-4 h-4 shrink-0 text-(--theme-muted)" />
            <div className="min-w-0">
              <span className="font-medium text-sm text-info truncate block max-w-45" title={team.name}>
                {team.name}
              </span>
              {team.description && (
                <span className="text-xs text-(--theme-muted) truncate block max-w-45">
                  {team.description}
                </span>
              )}
            </div>
            {team.archived && (
              <Badge variant="ghost" className="text-[10px] font-mono">Archived</Badge>
            )}
          </div>
        );
      },
    },
    {
      accessorKey: 'teamMode',
      header: 'Mode',
      cell: ({ getValue }) => {
        const mode = getValue() as string | undefined;
        return mode
          ? <Badge variant="neutral" outline className="text-xs font-mono">{mode}</Badge>
          : <span className="text-xs text-(--theme-muted) italic">N/A</span>;
      },
    },
    {
      id: 'health',
      header: 'Members',
      cell: ({ row }) => {
        const team = row.original;
        const total = team.memberCount ?? 0;
        const active = team.activeMemberCount ?? 0;
        const allActive = total > 0 && active === total;
        return (
          <div className="flex items-center gap-2">
            <span className="font-mono text-xs">{total}</span>
            {total > 0 && (
              <span className={`text-[10px] ${allActive ? 'text-success' : 'text-warning'}`}>
                ({active} active)
              </span>
            )}
          </div>
        );
      },
    },
    {
      id: 'leader',
      header: 'Leader',
      cell: ({ row }) => {
        const team = row.original;
        const name = team.leaderAgentName;
        if (name) {
          return <span className="text-xs text-primary truncate max-w-30 block" title={name}>{name}</span>;
        }
        if (team.leaderId) {
          return <span className="font-mono text-xs text-(--theme-muted) truncate max-w-30 block" title={team.leaderId}>{team.leaderId}</span>;
        }
        return <span className="text-xs text-(--theme-muted) italic">—</span>;
      },
    },
    {
      accessorKey: 'updatedAt',
      header: 'Updated',
      cell: ({ getValue }) => (
        <span className="text-xs text-(--theme-muted) whitespace-nowrap">
          {new Date(getValue() as string).toLocaleDateString()}
        </span>
      ),
    },
    {
      id: 'actions',
      header: '',
      enableSorting: false,
      cell: ({ row }) => {
        const team = row.original;
        return (
          <div className="flex items-center justify-end gap-1">
            <button
              type="button"
              onClick={() => navigate(`/teams/${team.id}`)}
              aria-label="View team details"
              title="View Details"
              className="btn btn-ghost btn-sm btn-square rounded-md text-(--theme-muted) hover:text-(--theme-foreground)"
            >
              <LuEye className="w-4 h-4" />
            </button>
            <button
              type="button"
              onClick={() => navigate(`/teams/${team.id}`)}
              aria-label="Edit team"
              title="Edit"
              className="btn btn-ghost btn-sm btn-square rounded-md text-(--theme-muted) hover:text-(--theme-foreground)"
            >
              <LuPencil className="w-4 h-4" />
            </button>
            <button
              type="button"
              onClick={() => navigate(`/chat/${team.id}`)}
              aria-label="Chat with team"
              title="Chat"
              className="btn btn-ghost btn-sm btn-square rounded-md text-(--theme-muted) hover:text-info"
            >
              <LuMessageSquare className="w-4 h-4" />
            </button>
            <button
              type="button"
              onClick={() => handleClone(team)}
              aria-label="Clone team"
              title="Clone"
              className="btn btn-ghost btn-sm btn-square rounded-md text-(--theme-muted) hover:text-(--theme-foreground)"
            >
              <LuCopy className="w-4 h-4" />
            </button>
            <button
              type="button"
              onClick={() => handleArchiveClick(team)}
              aria-label={team.archived ? 'Restore team' : 'Archive team'}
              title={team.archived ? 'Restore' : 'Archive'}
              className={`btn btn-ghost btn-sm btn-square rounded-md text-(--theme-muted) ${team.archived ? 'hover:text-success' : 'hover:text-warning'}`}
            >
              {team.archived ? <LuArchiveRestore className="w-4 h-4" /> : <LuArchive className="w-4 h-4" />}
            </button>
            {team.archived && (
              <button
                type="button"
                onClick={() => { setDeleteError(null); setDeleteTarget(team); }}
                aria-label="Delete team permanently"
                title="Delete permanently"
                className="btn btn-ghost btn-sm btn-square rounded-md text-(--theme-muted) hover:text-error"
              >
                <LuTrash2 className="w-4 h-4" />
              </button>
            )}
          </div>
        );
      },
    },
  ], []);

  return (
    <PageContainer variant="dashboard">
      <PageHeader
        icon={LuUsers}
        title="Teams"
        subtitle="Manage agent orchestration teams and leadership roles."
        actions={
          <div className="flex items-center gap-2">
            <Button variant="ghost" size="sm" onClick={() => navigate('/teams/manifests')} className="gap-1.5">
              <LuFileText className="w-4 h-4" /> Manifests
            </Button>
            <Button size="sm" onClick={() => navigate('/teams/new')} className="gap-1.5">
              <LuPlus className="w-4 h-4" /> Create Team
            </Button>
          </div>
        }
      />

      {error && <Alert severity="error" title="Error">{error}</Alert>}
      {feedback && (
        <Alert
          severity={feedback.type === 'success' ? 'success' : 'error'}
          description={feedback.message}
          dismissible
          onClose={() => setFeedback(null)}
        />
      )}

      {/* Search & Filter Bar */}
      <div className="flex flex-wrap items-center gap-3">
        <div className="relative flex-1 max-w-sm">
          <LuSearch className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-(--theme-muted)" />
          <input
            type="text"
            placeholder="Search teams..."
            className="input input-bordered input-sm w-full pl-9"
            value={search}
            onChange={e => setSearch(e.target.value)}
          />
        </div>
        <label className="label cursor-pointer gap-2">
          <span className="label-text text-sm text-(--theme-muted)">Show Archived</span>
          <input
            type="checkbox"
            className="toggle toggle-primary toggle-sm"
            checked={showArchived}
            onChange={(e) => { setShowArchived(e.target.checked); setPageIndex(0); }}
          />
        </label>
      </div>

      {loading ? (
        <div className="space-y-2">
          {[1, 2, 3, 4].map(i => <div key={i} className="h-12 bg-obsidian-elevated/50 rounded-md animate-pulse" />)}
        </div>
      ) : (
        <DataTable
          columns={columns}
          data={teams}
          manualPagination
          pageIndex={pageIndex}
          pageSize={PAGE_SIZE}
          totalElements={totalElements}
          onPageChange={setPageIndex}
          emptyMessage="No teams found. Create a team to orchestrate agent collaboration."
        />
      )}

      {/* Archive/Restore Confirmation Dialog */}
      <Dialog
        isOpen={archiveOpen}
        setIsOpen={setArchiveOpen}
        title={archiveTarget?.archived ? 'Restore Team' : 'Archive Team'}
        content={
          archiveTarget?.archived
            ? `Restore "${archiveTarget?.name}"? It will reappear in the default teams list.`
            : `Archive "${archiveTarget?.name}"? It will be hidden from the default list but can be restored later.`
        }
        severity={archiveTarget?.archived ? 'info' : 'warning'}
        confirmLabel={archiveTarget?.archived ? 'Restore' : 'Archive'}
        onConfirm={handleArchiveConfirm}
        onCancel={() => setArchiveTarget(null)}
      />

      {/* Hard-Delete Confirmation Dialog */}
      <Dialog
        isOpen={deleteTarget !== null}
        setIsOpen={(open) => {
          if (!open) {
            setDeleteTarget(null);
            setDeleteError(null);
          }
        }}
        title="Delete team permanently"
        content={
          deleteError
            ? `Failed to delete "${deleteTarget?.name}": ${deleteError}`
            : `Permanently delete "${deleteTarget?.name}"? This cannot be undone. Member assignments, history, and any orphaned references will be cleared.`
        }
        severity="error"
        confirmLabel={deleting ? 'Deleting…' : 'Delete permanently'}
        cancelLabel="Cancel"
        shouldCloseOnConfirm={false}
        onConfirm={handleDeleteConfirm}
        onCancel={() => {
          setDeleteTarget(null);
          setDeleteError(null);
        }}
      />
    </PageContainer>
  );
};
