// Teams — list, select → members + health.

import { useState } from 'react';
import { Panel, QueryState, rows, useGet, Json } from '../lib/ui';

interface Team { id: string; name: string; strategy?: string; description?: string; [k: string]: unknown }

export function TeamsPanel() {
  const teams = useGet(['teams'], (c) => c.http.get<unknown>('/v1/teams'));
  const [id, setId] = useState('');
  const members = useGet(['team-members', id], (c) => c.http.get<unknown>(`/v1/teams/${id}/members`), !!id);
  const health = useGet(['team-health', id], (c) => c.http.get<unknown>(`/v1/teams/${id}/health`), !!id);
  const list = rows<Team>(teams.data);

  return (
    <Panel title="Teams" subtitle={`${list.length}`}>
      <QueryState q={teams} />
      <div className="grid2">
        <div className="card">
          <h3>Teams</h3>
          <table className="data">
            <thead><tr><th>name</th><th>strategy</th></tr></thead>
            <tbody>
              {list.map((t) => (
                <tr key={t.id} className={t.id === id ? 'sel' : ''} onClick={() => setId(t.id)}>
                  <td>{t.name}</td><td><span className="chip">{t.strategy ?? '—'}</span></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="card">
          <h3>Members + health</h3>
          {!id && <p className="muted">select a team</p>}
          {id && <><QueryState q={members} /><Json value={members.data} /><QueryState q={health} /><Json value={health.data} /></>}
        </div>
      </div>
    </Panel>
  );
}
