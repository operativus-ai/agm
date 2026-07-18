import React, { useEffect, useState } from 'react';
import type { AgentConfig } from '../../../shared/types/api';
import type { AgentSummary } from '../../../shared/types/api';
import { KnowledgeBasesApi } from '../api/knowledge-bases-api';
import { AgentsApi } from '../../agents/api/agents-api';
import { Badge } from '../../../shared/components/ui/Badge';
import { Button } from '../../../shared/components/ui/Button';
import { LuTrash2, LuPlus, LuBrainCircuit } from 'react-icons/lu';

interface AssignedAgentsPanelProps {
  kbId: string;
  kbName: string;
}

export const AssignedAgentsPanel: React.FC<AssignedAgentsPanelProps> = ({ kbId, kbName }) => {
  const [assigned, setAssigned] = useState<AgentSummary[]>([]);
  const [allAgents, setAllAgents] = useState<AgentConfig[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const [selectedAgentId, setSelectedAgentId] = useState('');

  const loadAssigned = async () => {
    try {
      const agents = await KnowledgeBasesApi.getAssignedAgents(kbId);
      setAssigned(agents);
    } catch {
      setError('Failed to load assigned agents');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    setLoading(true);
    setError(null);
    loadAssigned();
    AgentsApi.getAgents().then(setAllAgents).catch(() => {});
  }, [kbId]);

  const handleAdd = async () => {
    if (!selectedAgentId) return;
    try {
      await KnowledgeBasesApi.assignAgent(kbId, selectedAgentId);
      setSelectedAgentId('');
      setAdding(false);
      await loadAssigned();
    } catch (err: any) {
      setError(err.message || 'Failed to assign agent');
    }
  };

  const handleRemove = async (agentId: string) => {
    try {
      await KnowledgeBasesApi.removeAgent(kbId, agentId);
      setAssigned(prev => prev.filter(a => a.agentId !== agentId));
    } catch (err: any) {
      setError(err.message || 'Failed to remove agent');
    }
  };

  const assignedIds = new Set(assigned.map(a => a.agentId));
  const unassigned = allAgents.filter(a => !assignedIds.has(a.agentId));

  return (
    <div className="bg-(--theme-card) p-5 rounded-xl border border-(--theme-muted)/10 shadow-sm space-y-4">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <LuBrainCircuit className="w-4 h-4 text-primary" />
          <span className="font-semibold text-sm">Agents using &quot;{kbName}&quot;</span>
          <Badge variant="neutral" className="text-[10px]">{assigned.length}</Badge>
        </div>
        {!adding && unassigned.length > 0 && (
          <Button size="sm" variant="outline" className="gap-1.5" onClick={() => setAdding(true)}>
            <LuPlus className="w-3.5 h-3.5" /> Assign Agent
          </Button>
        )}
      </div>

      {error && <p className="text-xs text-error">{error}</p>}

      {adding && (
        <div className="flex gap-2 items-center">
          <select
            className="select select-sm select-bordered flex-1 text-sm"
            value={selectedAgentId}
            onChange={e => setSelectedAgentId(e.target.value)}
          >
            <option value="">Select agent…</option>
            {unassigned.map(a => (
              <option key={a.agentId} value={a.agentId}>{a.name}</option>
            ))}
          </select>
          <Button size="sm" variant="primary" disabled={!selectedAgentId} onClick={handleAdd}>Add</Button>
          <Button size="sm" variant="ghost" onClick={() => { setAdding(false); setSelectedAgentId(''); }}>Cancel</Button>
        </div>
      )}

      {loading ? (
        <div className="flex gap-2 py-2">
          {[1, 2].map(i => <div key={i} className="h-7 w-28 bg-obsidian-elevated/50 rounded animate-pulse" />)}
        </div>
      ) : assigned.length === 0 ? (
        <p className="text-sm text-(--theme-muted) opacity-60 italic">No agents assigned to this collection.</p>
      ) : (
        <div className="flex flex-wrap gap-2">
          {assigned.map(agent => (
            <div key={agent.agentId} className="flex items-center gap-1.5 bg-obsidian-elevated/60 px-3 py-1.5 rounded-lg text-sm">
              <span>{agent.name}</span>
              <button
                className="text-(--theme-muted) hover:text-error transition-colors ml-1"
                title="Remove"
                onClick={() => handleRemove(agent.agentId)}
              >
                <LuTrash2 className="w-3 h-3" />
              </button>
            </div>
          ))}
        </div>
      )}
    </div>
  );
};
