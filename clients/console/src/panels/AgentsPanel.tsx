// Mode A — Agents & Run. List agents, pick one, run with LIVE streaming
// (token deltas, tool trace, reasoning, usage) and inline HITL approve/reject.

import { useEffect, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import type { RequiredAction, StreamChunk, UsageSummary } from '@agm/sdk';
import { useClient } from '../lib/session';

type RunState = 'idle' | 'streaming' | 'paused';

export function AgentsPanel() {
  const client = useClient();
  const agents = useQuery({ queryKey: ['agents'], queryFn: () => client.listAgents() });

  const [agentId, setAgentId] = useState<string>('');
  const [prompt, setPrompt] = useState('Reply with the single word: pong');
  const [text, setText] = useState('');
  const [reasoning, setReasoning] = useState('');
  const [activity, setActivity] = useState<string[]>([]);
  const [usage, setUsage] = useState<UsageSummary | null>(null);
  const [runState, setRunState] = useState<RunState>('idle');
  const [pending, setPending] = useState<RequiredAction | null>(null);
  const [error, setError] = useState<string | null>(null);
  const sessionRef = useRef<string>(crypto.randomUUID());
  const abortRef = useRef<(() => void) | null>(null);

  useEffect(() => () => abortRef.current?.(), []);

  const chosen = agentId || agents.data?.[0]?.id || '';

  function handleChunk(c: StreamChunk) {
    switch (c.event) {
      case 'CONTENT_DELTA': setText((t) => t + (c.data ?? '')); return;
      case 'REASONING_DELTA': setReasoning((r) => r + (c.data ?? '')); return;
      case 'TOOL_START': setActivity((a) => [...a, `🔧 ${c.data ?? 'tool'}`]); return;
      case 'TOOL_END': setActivity((a) => [...a, `✅ ${c.data ?? 'tool done'}`]); return;
      case 'TOOL_ERROR': setActivity((a) => [...a, `⚠️ ${c.data ?? 'tool error'}`]); return;
      case 'METRICS':
        try { setUsage(JSON.parse(c.data ?? '{}')); } catch { /* ignore */ }
        return;
      case 'PAUSED': {
        let action: RequiredAction = { message: c.data };
        try { action = JSON.parse(c.data ?? '{}'); } catch { /* keep raw */ }
        setPending(action);
        setRunState('paused');
        return;
      }
      case 'ERROR': setError(c.data ?? 'stream error'); return;
    }
    if (!c.event && c.content) setText((t) => t + c.content);
  }

  function run() {
    if (!chosen || runState !== 'idle') return;
    setText(''); setReasoning(''); setActivity([]); setUsage(null); setError(null);
    setRunState('streaming');
    sessionRef.current = crypto.randomUUID();
    abortRef.current = client.stream(
      chosen,
      { message: prompt, stream: true, sessionId: sessionRef.current },
      {
        onChunk: handleChunk,
        onError: (e) => { setError(e.message); setRunState('idle'); },
        onComplete: () => setRunState((s) => (s === 'paused' ? s : 'idle')),
      },
    );
  }

  async function resolve(decision: 'APPROVED' | 'REJECTED') {
    if (!pending) return;
    try {
      if (pending.approvalId) await client.resolveToolApproval(pending.approvalId, decision);
      else if (pending.escalationId) await client.resolveEscalation(pending.escalationId, decision);
      setActivity((a) => [...a, decision === 'APPROVED' ? '👍 approved — resuming' : '👎 rejected']);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setPending(null);
      setRunState('idle');
    }
  }

  return (
    <div className="panel two-col">
      <div className="col left">
        <h2>Agents & Run</h2>
        {agents.isLoading && <p className="muted">Loading agents…</p>}
        {agents.error && <div className="error">{(agents.error as Error).message}</div>}
        <label>
          Agent
          <select value={chosen} onChange={(e) => setAgentId(e.target.value)}>
            {agents.data?.map((a) => (
              <option key={a.id} value={a.id}>{a.name || a.id}</option>
            ))}
          </select>
        </label>
        <label>
          Prompt
          <textarea value={prompt} onChange={(e) => setPrompt(e.target.value)} rows={4} />
        </label>
        <button onClick={run} disabled={runState !== 'idle' || !chosen}>
          {runState === 'streaming' ? 'Streaming…' : runState === 'paused' ? 'Paused (resolve →)' : 'Run (stream)'}
        </button>
        <p className="muted mono">session {sessionRef.current.slice(0, 8)}</p>
      </div>

      <div className="col right">
        <h3>Output</h3>
        {reasoning && <details><summary>reasoning</summary><pre className="reason">{reasoning}</pre></details>}
        {activity.length > 0 && <div className="activity">{activity.map((a, i) => <div key={i}>{a}</div>)}</div>}
        <pre className="output">{text || (runState === 'streaming' ? '…' : '')}</pre>
        {error && <div className="error">{error}</div>}
        {usage && (
          <div className="usage muted">
            {usage.totalTokens ?? '?'} tokens
            {usage.costUsd !== undefined && ` · $${usage.costUsd.toFixed(4)}`}
            {usage.model && ` · ${usage.model}`}
          </div>
        )}
        {runState === 'paused' && pending && (
          <div className="hitl">
            <strong>⏸ Approval required</strong>
            <p>{pending.toolName ? `Tool "${pending.toolName}" needs approval.` : pending.message || 'Run paused for approval.'}</p>
            <div className="row">
              <button onClick={() => resolve('APPROVED')}>Approve</button>
              <button className="danger" onClick={() => resolve('REJECTED')}>Reject</button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
