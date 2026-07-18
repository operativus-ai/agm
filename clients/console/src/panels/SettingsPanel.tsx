// Settings — read the platform settings map (read-only in the console).

import { Panel, QueryState, useGet } from '../lib/ui';

export function SettingsPanel() {
  const q = useGet(['settings'], (c) => c.http.get<Record<string, unknown>>('/v1/settings'));

  return (
    <Panel title="Settings" subtitle="read-only">
      <QueryState q={q} />
      <table className="data">
        <thead><tr><th>key</th><th>value</th></tr></thead>
        <tbody>
          {q.data && Object.entries(q.data).map(([k, v]) => (
            <tr key={k}>
              <td className="mono">{k}</td>
              <td>{typeof v === 'object' ? JSON.stringify(v) : String(v)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="muted">Mutating settings (PUT /api/v1/settings) is intentionally not exposed here — use the admin UI.</p>
    </Panel>
  );
}
