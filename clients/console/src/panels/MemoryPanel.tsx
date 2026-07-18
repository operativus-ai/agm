// Memory — list agentic memories + stats + topics.

import { Panel, QueryState, rows, useGet, Json } from '../lib/ui';

interface MemoryRow { id?: string; content?: string; agentId?: string; createdAt?: string; [k: string]: unknown }

export function MemoryPanel() {
  const mem = useGet(['memories'], (c) => c.http.get<unknown>('/memories?page=0&size=50'));
  const stats = useGet(['memory-stats'], (c) => c.http.get<unknown>('/memories/stats'));
  const topics = useGet(['memory-topics'], (c) => c.http.get<unknown>('/memories/topics'));
  const items = rows<MemoryRow>(mem.data);

  return (
    <Panel title="Memory" subtitle={`${items.length}`}>
      <div className="grid2">
        <div className="card">
          <h3>Memories</h3>
          <QueryState q={mem} />
          <table className="data">
            <thead><tr><th>agent</th><th>content</th></tr></thead>
            <tbody>
              {items.map((m, i) => (
                <tr key={m.id ?? i}>
                  <td className="mono muted">{m.agentId?.slice(0, 10) ?? '—'}</td>
                  <td>{(m.content ?? '').slice(0, 120)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="card">
          <h3>Stats</h3><QueryState q={stats} /><Json value={stats.data} />
          <h3>Topics</h3><QueryState q={topics} /><Json value={topics.data} />
        </div>
      </div>
    </Panel>
  );
}
