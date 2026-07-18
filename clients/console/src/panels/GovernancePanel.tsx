// Mode A — Governance. Provider credentials (list). Read-only in the console:
// upsert/delete are destructive to a shared org's live key, so they stay CLI/Phase-5.

import { useQuery } from '@tanstack/react-query';
import { useClient } from '../lib/session';

export function GovernancePanel() {
  const client = useClient();
  const creds = useQuery({ queryKey: ['provider-credentials'], queryFn: () => client.listProviderCredentials() });

  return (
    <div className="panel">
      <h2>Provider credentials</h2>
      {creds.isLoading && <p className="muted">loading…</p>}
      {creds.error && <div className="error">{(creds.error as Error).message}</div>}
      {creds.data && creds.data.length === 0 && (
        <p className="muted">
          No credentials configured — LLM runs return 400 “No API key configured for provider …” until one is added
          (via the admin UI or the CLI).
        </p>
      )}
      <table className="data">
        <thead><tr><th>provider</th><th>label</th><th>id</th></tr></thead>
        <tbody>
          {creds.data?.map((c) => (
            <tr key={c.id}>
              <td><span className="chip">{c.provider}</span></td>
              <td>{c.label ?? '—'}</td>
              <td className="mono muted">{c.id}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
