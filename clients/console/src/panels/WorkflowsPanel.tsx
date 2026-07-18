// Mode A — Workflows. List; author a linear 2-node AGENT DAG; validate; run; poll.

import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { createLinearAgentWorkflow } from '@agm/sdk';
import { useClient } from '../lib/session';

export function WorkflowsPanel() {
  const client = useClient();
  const workflows = useQuery({ queryKey: ['workflows'], queryFn: () => client.http.get<{ content: Array<{ id: string; name: string; stepCount?: number }> }>('/v1/workflows?page=0&size=50') });
  const agents = useQuery({ queryKey: ['agents'], queryFn: () => client.listAgents() });

  const [agentId, setAgentId] = useState('');
  const [log, setLog] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);

  const chosen = agentId || agents.data?.[0]?.id || '';
  const say = (m: string) => setLog((l) => [...l, m]);

  async function build() {
    if (!chosen) { say('pick an agent first'); return; }
    setBusy(true); setLog([]);
    try {
      say('creating workflow + 2 agent steps + edge…');
      const wf = await createLinearAgentWorkflow(client, `console-${Date.now()}-`, chosen);
      say(`workflow ${wf.workflowId} · ${wf.stepIds.length} steps`);
      const validation = await client.validateWorkflow(wf.workflowId);
      say(`validation: ${validation.valid === false ? 'INVALID ' + JSON.stringify(validation.errors ?? '') : 'ok'}`);
      const exec = await client.runWorkflow(wf.workflowId, 'Say hello.', crypto.randomUUID());
      say(`run started jobId=${exec.jobId}`);
      for (let i = 0; i < 30; i++) {
        const page = await client.workflowRuns(wf.workflowId);
        const run = (page.content ?? []).find((r) => r.status === 'COMPLETED' || r.status === 'FAILED');
        if (run) { say(`run terminal: ${run.status}`); break; }
        await new Promise((r) => setTimeout(r, 3000));
      }
      workflows.refetch();
    } catch (e) { say(`error: ${(e as Error).message}`); } finally { setBusy(false); }
  }

  return (
    <div className="panel">
      <h2>Workflows</h2>
      <div className="grid2">
        <div className="card">
          <h3>Existing</h3>
          {workflows.isLoading && <p className="muted">loading…</p>}
          {workflows.error && <div className="error">{(workflows.error as Error).message}</div>}
          <table className="data">
            <thead><tr><th>name</th><th>steps</th></tr></thead>
            <tbody>
              {workflows.data?.content?.map((w) => <tr key={w.id}><td>{w.name}</td><td>{w.stepCount ?? '—'}</td></tr>)}
            </tbody>
          </table>
        </div>
        <div className="card">
          <h3>Author + run a linear DAG</h3>
          <label>Agent for both nodes
            <select value={chosen} onChange={(e) => setAgentId(e.target.value)}>
              {agents.data?.map((a) => <option key={a.id} value={a.id}>{a.name || a.id}</option>)}
            </select>
          </label>
          <button onClick={build} disabled={busy || !chosen}>Build → validate → run</button>
          <pre className="output small">{log.join('\n')}</pre>
        </div>
      </div>
    </div>
  );
}
