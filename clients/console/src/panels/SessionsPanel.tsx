// Mode A — Sessions. List sessions for an agent, drill into a session's runs.

import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useClient } from '../lib/session';

export function SessionsPanel() {
  const client = useClient();
  const agents = useQuery({ queryKey: ['agents'], queryFn: () => client.listAgents() });
  const [agentId, setAgentId] = useState('');
  const [sessionId, setSessionId] = useState('');

  const chosen = agentId || agents.data?.[0]?.id || '';
  const sessions = useQuery({
    queryKey: ['sessions', chosen],
    queryFn: () => client.listSessions(chosen),
    enabled: !!chosen,
  });
  const runs = useQuery({
    queryKey: ['session-runs', sessionId],
    queryFn: () => client.sessionRuns(sessionId),
    enabled: !!sessionId,
  });

  return (
    <div className="panel">
      <h2>Sessions</h2>
      <label>Agent
        <select value={chosen} onChange={(e) => { setAgentId(e.target.value); setSessionId(''); }}>
          {agents.data?.map((a) => <option key={a.id} value={a.id}>{a.name || a.id}</option>)}
        </select>
      </label>
      <div className="grid2">
        <div className="card">
          <h3>Sessions {sessions.data ? `(${sessions.data.content?.length ?? 0})` : ''}</h3>
          {sessions.isLoading && <p className="muted">loading…</p>}
          <table className="data">
            <thead><tr><th>id</th><th>title</th><th>updated</th></tr></thead>
            <tbody>
              {sessions.data?.content?.map((s) => (
                <tr key={s.id} className={s.id === sessionId ? 'sel' : ''} onClick={() => setSessionId(s.id)}>
                  <td className="mono">{s.id.slice(0, 8)}</td>
                  <td>{s.title ?? '—'}</td>
                  <td className="muted">{s.updatedAt?.slice(0, 19).replace('T', ' ')}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="card">
          <h3>Runs {runs.data ? `(${runs.data.length})` : ''}</h3>
          {!sessionId && <p className="muted">select a session</p>}
          {runs.isLoading && <p className="muted">loading…</p>}
          <table className="data">
            <thead><tr><th>run</th><th>status</th><th>input</th></tr></thead>
            <tbody>
              {runs.data?.map((r) => (
                <tr key={r.id}>
                  <td className="mono">{r.id.slice(0, 8)}</td>
                  <td><span className="chip">{r.status}</span></td>
                  <td className="muted">{(r.input ?? '').slice(0, 60)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
