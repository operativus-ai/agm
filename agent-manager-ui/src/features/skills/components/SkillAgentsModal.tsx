import React, { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { LuTrash2, LuPlus } from 'react-icons/lu';
import { Dialog } from '../../../shared/components/ui/Dialog';
import { Button } from '../../../shared/components/ui/Button';
import { Select } from '../../../shared/components/ui/Select';
import { AgentsApi } from '../../agents/api/agents-api';
import { skillsApi, type Skill } from '../api/skillsApi';

export interface SkillAgentsModalProps {
  skill: Skill | null;
  onClose: () => void;
}

export const SkillAgentsModal: React.FC<SkillAgentsModalProps> = ({ skill, onClose }) => {
  const qc = useQueryClient();
  const isOpen = skill !== null;
  const skillId = skill?.id ?? '';
  const [picked, setPicked] = useState('');
  const [actionError, setActionError] = useState<string | null>(null);

  const bindingsKey = ['skills', skillId, 'agents'];

  const { data: bindings = [], isLoading: loadingBindings } = useQuery({
    queryKey: bindingsKey,
    queryFn: () => skillsApi.listAgents(skillId),
    enabled: isOpen,
  });

  const { data: agents = [] } = useQuery({
    queryKey: ['agents', 'all-for-skill-picker'],
    queryFn: () => AgentsApi.getAgents(),
    enabled: isOpen,
    staleTime: 60_000,
  });

  const nameOf = useMemo(() => {
    const m = new Map(agents.map(a => [a.agentId, a.name]));
    return (agentId: string) => m.get(agentId) ?? agentId;
  }, [agents]);

  const boundIds = useMemo(() => new Set(bindings.map(b => b.agentId)), [bindings]);
  const attachable = useMemo(
    () => agents.filter(a => !boundIds.has(a.agentId)),
    [agents, boundIds],
  );

  const refresh = () => qc.invalidateQueries({ queryKey: bindingsKey });

  const attachMutation = useMutation({
    mutationFn: (agentId: string) => skillsApi.attachAgent(skillId, agentId),
    onSuccess: () => { setActionError(null); setPicked(''); refresh(); },
    onError: (err: unknown) => setActionError(err instanceof Error ? err.message : 'Failed to attach agent.'),
  });

  const detachMutation = useMutation({
    mutationFn: (agentId: string) => skillsApi.detachAgent(skillId, agentId),
    onSuccess: () => { setActionError(null); refresh(); },
    onError: (err: unknown) => setActionError(err instanceof Error ? err.message : 'Failed to detach agent.'),
  });

  const busy = attachMutation.isPending || detachMutation.isPending;

  return (
    <Dialog
      isOpen={isOpen}
      setIsOpen={(open) => { if (!open) { setActionError(null); setPicked(''); onClose(); } }}
      title={skill ? `Agents using "${skill.name}"` : 'Manage agents'}
      confirmLabel="Done"
      canBeCanceled={false}
      shouldCloseOnConfirm={false}
      onConfirm={() => { setActionError(null); setPicked(''); onClose(); }}
    >
      <div className="space-y-4">
        {actionError && (
          <div className="text-sm text-error bg-error/10 border border-error/30 rounded px-3 py-2">
            {actionError}
          </div>
        )}

        {/* Attach */}
        <div className="flex items-end gap-2">
          <div className="flex-1">
            <label className="text-xs font-bold text-theme-muted uppercase tracking-wider">Attach an agent</label>
            <Select
              value={picked}
              onValueChange={setPicked}
              disabled={busy || attachable.length === 0}
              placeholder={attachable.length === 0 ? 'All agents already attached' : 'Select an agent…'}
              options={attachable.map(a => ({ value: a.agentId, label: a.name }))}
            />
          </div>
          <Button
            variant="primary"
            disabled={!picked || busy}
            onClick={() => picked && attachMutation.mutate(picked)}
          >
            <LuPlus className="mr-1.5" size={14} /> Attach
          </Button>
        </div>

        {/* Current bindings */}
        <div className="border border-obsidian-stroke/50 rounded-lg overflow-hidden">
          {loadingBindings ? (
            <div className="p-6 text-center text-theme-muted text-sm">Loading…</div>
          ) : bindings.length === 0 ? (
            <div className="p-6 text-center text-theme-muted text-sm">No agents use this skill yet.</div>
          ) : (
            <ul className="divide-y divide-obsidian-stroke/40">
              {bindings.map(b => (
                <li key={b.agentId} className="flex items-center justify-between px-3 py-2">
                  <div className="min-w-0">
                    <div className="text-sm truncate">{nameOf(b.agentId)}</div>
                    <div className="text-[11px] text-theme-muted font-mono">
                      {b.agentId} · priority {b.priority}
                    </div>
                  </div>
                  <button
                    type="button"
                    className="p-1.5 rounded hover:bg-error/10 text-error disabled:opacity-50"
                    onClick={() => detachMutation.mutate(b.agentId)}
                    disabled={busy}
                    title="Detach"
                  >
                    <LuTrash2 size={14} />
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>
    </Dialog>
  );
};
