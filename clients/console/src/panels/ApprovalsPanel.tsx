// HITL approvals inbox — pending approvals + resolve (approve/reject).

import { useState } from 'react';
import { useClient } from '../lib/session';
import { Panel, QueryState, rows, useGet } from '../lib/ui';

interface ApprovalRow {
  id: string;
  toolName?: string;
  runId?: string;
  agentId?: string;
  status?: string;
  createdAt?: string;
  [k: string]: unknown;
}

export function ApprovalsPanel() {
  const client = useClient();
  const q = useGet(['approvals-pending'], (c) => c.http.get<unknown>('/v1/approvals/pending?page=0&size=50'));
  const [busy, setBusy] = useState<string | null>(null);
  const items = rows<ApprovalRow>(q.data);

  async function resolve(id: string, decision: 'APPROVED' | 'REJECTED') {
    setBusy(id);
    try {
      await client.http.post(`/v1/approvals/${encodeURIComponent(id)}/resolve`, { decision });
      await q.refetch();
    } catch (e) {
      alert((e as Error).message);
    } finally {
      setBusy(null);
    }
  }

  return (
    <Panel title="Approvals (HITL inbox)" subtitle={`${items.length} pending`}>
      <QueryState q={q} />
      {!q.isLoading && !q.error && items.length === 0 && <p className="muted">No pending approvals. Trigger a HITL-gated tool run to populate this.</p>}
      <table className="data">
        <thead><tr><th>id</th><th>tool</th><th>run</th><th>status</th><th></th></tr></thead>
        <tbody>
          {items.map((a) => (
            <tr key={a.id}>
              <td className="mono">{a.id.slice(0, 8)}</td>
              <td>{a.toolName ?? '—'}</td>
              <td className="mono muted">{a.runId?.slice(0, 8) ?? '—'}</td>
              <td><span className="chip">{a.status ?? 'PENDING'}</span></td>
              <td className="row">
                <button disabled={busy === a.id} onClick={() => resolve(a.id, 'APPROVED')}>Approve</button>
                <button className="danger" disabled={busy === a.id} onClick={() => resolve(a.id, 'REJECTED')}>Reject</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </Panel>
  );
}
