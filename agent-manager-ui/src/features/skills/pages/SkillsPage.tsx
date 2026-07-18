import React, { useMemo, useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import type { ColumnDef } from '@tanstack/react-table';
import { LuSparkles, LuPencil, LuTrash2, LuPlus, LuUsers } from 'react-icons/lu';

import { PageContainer } from '../../../shared/components/ui/PageContainer';
import { PageHeader } from '../../../shared/components/ui/PageHeader';
import { Button } from '../../../shared/components/ui/Button';
import { DataTable } from '../../../shared/components/ui/DataTable';
import { Dialog } from '../../../shared/components/ui/Dialog';

import { skillsApi, type Skill } from '../api/skillsApi';
import { SkillFormModal } from '../components/SkillFormModal';
import { SkillAgentsModal } from '../components/SkillAgentsModal';

export const SkillsPage: React.FC = () => {
  const qc = useQueryClient();

  const { data, isLoading, error } = useQuery({
    queryKey: ['skills', 'list'],
    queryFn: () => skillsApi.list(),
    staleTime: 30_000,
    retry: false, // a 404 means the feature is disabled — don't hammer it
  });
  const skills = data?.content ?? [];

  const [isFormOpen, setIsFormOpen] = useState(false);
  const [editing, setEditing] = useState<Skill | null>(null);
  const [deleting, setDeleting] = useState<Skill | null>(null);
  const [managingAgents, setManagingAgents] = useState<Skill | null>(null);

  const deleteMutation = useMutation({
    mutationFn: (id: string) => skillsApi.delete(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['skills'] });
      setDeleting(null);
    },
  });

  const toggleMutation = useMutation({
    mutationFn: (skill: Skill) =>
      skillsApi.update(skill.id, {
        name: skill.name,
        description: skill.description ?? undefined,
        systemPromptSnippet: skill.systemPromptSnippet ?? undefined,
        allowedTools: skill.allowedTools,
        active: !skill.active,
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['skills'] }),
  });

  const openCreate = () => {
    setEditing(null);
    setIsFormOpen(true);
  };

  const openEdit = (skill: Skill) => {
    setEditing(skill);
    setIsFormOpen(true);
  };

  const columns = useMemo<ColumnDef<Skill, unknown>[]>(
    () => [
      {
        id: 'name',
        header: 'Name',
        cell: ({ row }) => (
          <div>
            <div className="font-semibold">{row.original.name}</div>
            {row.original.description && (
              <div className="text-xs text-theme-muted line-clamp-1">{row.original.description}</div>
            )}
          </div>
        ),
      },
      {
        id: 'tools',
        header: 'Allowed tools',
        cell: ({ row }) => {
          const tools = row.original.allowedTools ?? [];
          if (tools.length === 0) return <span className="text-theme-muted text-xs">—</span>;
          return (
            <div className="flex flex-wrap gap-1 max-w-[280px]">
              {tools.slice(0, 4).map(t => (
                <code key={t} className="font-mono text-[11px] bg-obsidian-elevated px-1.5 py-0.5 rounded">
                  {t}
                </code>
              ))}
              {tools.length > 4 && (
                <span className="text-[11px] text-theme-muted">+{tools.length - 4}</span>
              )}
            </div>
          );
        },
      },
      {
        id: 'active',
        header: 'Active',
        cell: ({ row }) => (
          <button
            type="button"
            className={`px-2 py-0.5 text-xs rounded border transition-colors ${
              row.original.active
                ? 'bg-success/10 text-success border-success/30 hover:bg-success/20'
                : 'bg-obsidian-elevated text-theme-muted border-obsidian-stroke hover:bg-obsidian-elevated/70'
            }`}
            onClick={() => toggleMutation.mutate(row.original)}
            disabled={toggleMutation.isPending}
            title={row.original.active ? 'Click to deactivate' : 'Click to activate'}
          >
            {row.original.active ? 'ON' : 'OFF'}
          </button>
        ),
      },
      {
        id: 'actions',
        header: '',
        cell: ({ row }) => (
          <div className="flex items-center gap-1 justify-end">
            <button
              type="button"
              className="p-1.5 rounded hover:bg-obsidian-elevated text-theme-muted hover:text-theme-foreground"
              onClick={() => setManagingAgents(row.original)}
              title="Manage agents"
            >
              <LuUsers size={14} />
            </button>
            <button
              type="button"
              className="p-1.5 rounded hover:bg-obsidian-elevated text-theme-muted hover:text-theme-foreground"
              onClick={() => openEdit(row.original)}
              title="Edit"
            >
              <LuPencil size={14} />
            </button>
            <button
              type="button"
              className="p-1.5 rounded hover:bg-error/10 text-error hover:text-error"
              onClick={() => setDeleting(row.original)}
              title="Delete"
            >
              <LuTrash2 size={14} />
            </button>
          </div>
        ),
      },
    ],
    [toggleMutation]
  );

  const isFeatureDisabled = (error as { status?: number } | null)?.status === 404;

  return (
    <PageContainer variant="dashboard">
      <PageHeader
        icon={LuSparkles}
        title="Skills"
        subtitle="Reusable system-prompt snippets + tool allowlists that can be attached to agents."
        actions={
          !isFeatureDisabled ? (
            <Button variant="primary" onClick={openCreate}>
              <LuPlus className="mr-1.5" size={14} />
              Create skill
            </Button>
          ) : undefined
        }
      />

      {isFeatureDisabled ? (
        <div className="bg-obsidian-card border border-obsidian-stroke/50 rounded-xl p-10 text-center text-theme-muted">
          The Skills feature is not enabled on this deployment
          (<code className="font-mono text-xs">agm.skills.enabled</code>). Contact an administrator to turn it on.
        </div>
      ) : (
        <>
          {error && (
            <div className="bg-error/10 border border-error/30 text-error text-sm rounded px-4 py-3 mb-4">
              Failed to load skills: {(error as Error).message}
            </div>
          )}

          <div className="bg-obsidian-card border border-obsidian-stroke/50 rounded-xl overflow-hidden">
            {isLoading ? (
              <div className="p-10 text-center text-theme-muted">Loading…</div>
            ) : skills.length === 0 ? (
              <div className="p-10 text-center text-theme-muted">
                No skills defined yet.
                <div className="mt-3">
                  <Button variant="outline" size="sm" onClick={openCreate}>
                    Create your first skill
                  </Button>
                </div>
              </div>
            ) : (
              <DataTable columns={columns} data={skills} enablePagination defaultPageSize={25} />
            )}
          </div>
        </>
      )}

      <SkillFormModal
        isOpen={isFormOpen}
        existing={editing}
        onClose={() => setIsFormOpen(false)}
        onSaved={() => qc.invalidateQueries({ queryKey: ['skills'] })}
      />

      <SkillAgentsModal skill={managingAgents} onClose={() => setManagingAgents(null)} />

      <Dialog
        isOpen={!!deleting}
        setIsOpen={(open) => !open && setDeleting(null)}
        title={deleting ? `Delete skill "${deleting.name}"?` : ''}
        severity="error"
        canBeCanceled
        shouldCloseOnConfirm={false}
        onConfirm={() => deleting && deleteMutation.mutate(deleting.id)}
        onCancel={() => setDeleting(null)}
      >
        <div className="text-sm text-theme-muted">
          This removes the skill and detaches it from any agents using it. This action cannot be undone.
        </div>
        {deleteMutation.error && (
          <div className="mt-3 text-sm text-error">{(deleteMutation.error as Error).message}</div>
        )}
      </Dialog>
    </PageContainer>
  );
};
