import { useQuery } from '@tanstack/react-query';
import { agentsApi } from '../api/agm';
import type { AgentSummary } from '../types';

export function AgentPickerPage({ onPick }: { onPick: (agent: AgentSummary) => void }) {
  const { data, isLoading, error } = useQuery({ queryKey: ['agents'], queryFn: agentsApi.list });

  if (isLoading) return <div className="center-card muted">Loading agents…</div>;
  if (error)
    return (
      <div className="center-card">
        <div className="error">Failed to list agents: {(error as Error).message}</div>
      </div>
    );

  return (
    <div className="page">
      <h2>Pick an agent</h2>
      <div className="agent-grid">
        {data?.map((agent) => (
          <button key={agent.id} className="agent-card" onClick={() => onPick(agent)}>
            <strong>{agent.name || agent.id}</strong>
            {agent.description && <span className="muted">{agent.description}</span>}
            {agent.model && <span className="chip">{agent.model}</span>}
          </button>
        ))}
        {data?.length === 0 && <p className="muted">No agents registered — create one in agent-manager-ui first.</p>}
      </div>
    </div>
  );
}
