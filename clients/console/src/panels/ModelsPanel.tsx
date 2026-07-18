// Models — catalog + per-model connection test.

import { useState } from 'react';
import { useClient } from '../lib/session';
import { Panel, QueryState, rows, useGet } from '../lib/ui';

interface Model { id: string; name?: string; provider?: string; modelType?: string; available?: boolean; [k: string]: unknown }

export function ModelsPanel() {
  const client = useClient();
  const q = useGet(['models'], (c) => c.http.get<unknown>('/models'));
  const [result, setResult] = useState<Record<string, string>>({});
  const list = rows<Model>(q.data);

  async function test(id: string) {
    setResult((r) => ({ ...r, [id]: 'testing…' }));
    try {
      await client.http.post(`/models/${encodeURIComponent(id)}/test`);
      setResult((r) => ({ ...r, [id]: '✓ ok' }));
    } catch (e) {
      setResult((r) => ({ ...r, [id]: `✗ ${(e as Error).message}` }));
    }
  }

  return (
    <Panel title="Models" subtitle={`${list.length}`}>
      <QueryState q={q} />
      <table className="data">
        <thead><tr><th>name</th><th>provider</th><th>type</th><th>available</th><th>connection</th></tr></thead>
        <tbody>
          {list.map((m) => (
            <tr key={m.id}>
              <td>{m.name ?? m.id}</td>
              <td><span className="chip">{m.provider ?? '—'}</span></td>
              <td className="muted">{m.modelType ?? '—'}</td>
              <td>{m.available ? '✓' : '—'}</td>
              <td className="row">
                <button onClick={() => test(m.id)}>Test</button>
                <span className="muted">{result[m.id] ?? ''}</span>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </Panel>
  );
}
