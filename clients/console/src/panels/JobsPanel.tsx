// Background jobs — the queue view.

import { Panel, QueryState, rows, useGet } from '../lib/ui';

interface Job { id?: string; jobId?: string; type?: string; status?: string; createdAt?: string; [k: string]: unknown }

export function JobsPanel() {
  const q = useGet(['bg-jobs'], (c) => c.http.get<unknown>('/v1/observability/background-jobs?page=0&size=50'));
  const list = rows<Job>(q.data);

  return (
    <Panel title="Background jobs" subtitle={`${list.length}`}>
      <QueryState q={q} />
      {!q.isLoading && list.length === 0 && <p className="muted">no background jobs</p>}
      <table className="data">
        <thead><tr><th>id</th><th>type</th><th>status</th><th>created</th></tr></thead>
        <tbody>
          {list.map((j, i) => (
            <tr key={j.id ?? j.jobId ?? i}>
              <td className="mono">{(j.id ?? j.jobId ?? '').slice(0, 10)}</td>
              <td>{j.type ?? '—'}</td>
              <td><span className="chip">{j.status ?? '—'}</span></td>
              <td className="muted">{j.createdAt?.slice(0, 19).replace('T', ' ') ?? ''}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </Panel>
  );
}
