// Mode A — Tools. The tool catalog (GET /api/tools).

import { useQuery } from '@tanstack/react-query';
import { useClient } from '../lib/session';

export function ToolsPanel() {
  const client = useClient();
  const tools = useQuery({ queryKey: ['tools'], queryFn: () => client.listTools() });

  return (
    <div className="panel">
      <h2>Tool catalog</h2>
      {tools.isLoading && <p className="muted">loading…</p>}
      {tools.error && <div className="error">{(tools.error as Error).message}</div>}
      {tools.data && <p className="muted">{tools.data.length} tools</p>}
      <table className="data">
        <thead><tr><th>id</th><th>label</th><th>category</th><th>description</th></tr></thead>
        <tbody>
          {tools.data?.map((t) => (
            <tr key={t.id}>
              <td className="mono">{t.id}</td>
              <td>{t.label}</td>
              <td><span className="chip">{t.categoryLabel ?? t.category ?? '—'}</span></td>
              <td className="muted">{t.desc ?? ''}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
