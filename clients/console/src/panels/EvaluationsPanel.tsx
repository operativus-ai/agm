// Evaluations — list suites, run a suite, view runs.

import { useState } from 'react';
import { useClient } from '../lib/session';
import { Panel, QueryState, rows, useGet, Json } from '../lib/ui';

interface Suite { id: string; name?: string; description?: string; [k: string]: unknown }

export function EvaluationsPanel() {
  const client = useClient();
  const suites = useGet(['eval-suites'], (c) => c.http.get<unknown>('/v1/evaluations/suites'));
  const [id, setId] = useState('');
  const runs = useGet(['eval-runs', id], (c) => c.http.get<unknown>(`/v1/evaluations/suites/${id}/runs`), !!id);
  const [busy, setBusy] = useState(false);
  const list = rows<Suite>(suites.data);

  async function run(sid: string) {
    setBusy(true);
    try { await client.http.post(`/v1/evaluations/suites/${encodeURIComponent(sid)}/run`); await runs.refetch(); }
    catch (e) { alert((e as Error).message); } finally { setBusy(false); }
  }

  return (
    <Panel title="Evaluations" subtitle={`${list.length} suites`}>
      <QueryState q={suites} />
      <div className="grid2">
        <div className="card">
          <h3>Suites</h3>
          <table className="data">
            <thead><tr><th>name</th><th></th></tr></thead>
            <tbody>
              {list.map((s) => (
                <tr key={s.id} className={s.id === id ? 'sel' : ''}>
                  <td onClick={() => setId(s.id)}>{s.name ?? s.id.slice(0, 8)}</td>
                  <td><button disabled={busy} onClick={() => run(s.id)}>Run</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="card">
          <h3>Runs</h3>
          {!id && <p className="muted">select a suite</p>}
          {id && <><QueryState q={runs} /><Json value={runs.data} /></>}
        </div>
      </div>
    </Panel>
  );
}
