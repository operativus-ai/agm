// Schedules — list, trigger now, view runs.

import { useState } from 'react';
import { useClient } from '../lib/session';
import { Panel, QueryState, rows, useGet, Json } from '../lib/ui';

interface Schedule { id: string; name?: string; cronExpression?: string; enabled?: boolean; targetType?: string; [k: string]: unknown }

export function SchedulesPanel() {
  const client = useClient();
  const q = useGet(['schedules'], (c) => c.http.get<unknown>('/v1/schedules'));
  const [id, setId] = useState('');
  const runs = useGet(['schedule-runs', id], (c) => c.http.get<unknown>(`/v1/schedules/${id}/runs`), !!id);
  const [busy, setBusy] = useState(false);
  const list = rows<Schedule>(q.data);

  async function trigger(sid: string) {
    setBusy(true);
    try { await client.http.post(`/v1/schedules/${encodeURIComponent(sid)}/trigger`); await runs.refetch(); }
    catch (e) { alert((e as Error).message); } finally { setBusy(false); }
  }

  return (
    <Panel title="Schedules" subtitle={`${list.length}`}>
      <QueryState q={q} />
      <div className="grid2">
        <div className="card">
          <h3>Schedules</h3>
          <table className="data">
            <thead><tr><th>name</th><th>cron</th><th>on</th><th></th></tr></thead>
            <tbody>
              {list.map((s) => (
                <tr key={s.id} className={s.id === id ? 'sel' : ''}>
                  <td onClick={() => setId(s.id)}>{s.name ?? s.id.slice(0, 8)}</td>
                  <td className="mono">{s.cronExpression ?? '—'}</td>
                  <td>{s.enabled ? '✓' : '—'}</td>
                  <td><button disabled={busy} onClick={() => trigger(s.id)}>Trigger</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="card">
          <h3>Runs</h3>
          {!id && <p className="muted">select a schedule</p>}
          {id && <><QueryState q={runs} /><Json value={runs.data} /></>}
        </div>
      </div>
    </Panel>
  );
}
