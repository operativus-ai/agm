// A2A — peers + agent cards (read).

import { Panel, QueryState, rows, useGet, Json } from '../lib/ui';

interface Peer { alias?: string; url?: string; trusted?: boolean; status?: string; [k: string]: unknown }

export function A2APanel() {
  const peers = useGet(['a2a-peers'], (c) => c.http.get<unknown>('/v1/a2a/peers'));
  const cards = useGet(['a2a-cards'], (c) => c.http.get<unknown>('/v1/a2a/cards'));
  const list = rows<Peer>(peers.data);

  return (
    <Panel title="A2A (agent-to-agent)" subtitle={`${list.length} peers`}>
      <div className="grid2">
        <div className="card">
          <h3>Peers</h3>
          <QueryState q={peers} />
          {!peers.isLoading && list.length === 0 && <p className="muted">no peers registered</p>}
          <table className="data">
            <thead><tr><th>alias</th><th>url</th><th>trusted</th></tr></thead>
            <tbody>
              {list.map((p, i) => (
                <tr key={p.alias ?? i}>
                  <td>{p.alias ?? '—'}</td>
                  <td className="mono muted">{p.url ?? '—'}</td>
                  <td>{p.trusted ? '✓' : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="card">
          <h3>Agent cards (exported)</h3>
          <QueryState q={cards} />
          <Json value={cards.data} />
        </div>
      </div>
    </Panel>
  );
}
