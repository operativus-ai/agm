import { useEffect, useRef, useState } from 'react';
import { approvalsApi } from '../api/agm';
import { streamRun } from '../api/stream';
import type { AgentSummary, RequiredAction, StreamChunk, UsageSummary } from '../types';

interface Message {
  role: 'user' | 'assistant';
  content: string;
  thoughts?: string;
  activity: string[];
  error?: string;
  usage?: UsageSummary;
}

// A run is an async state machine, not request/response: it can PAUSE mid-stream
// for HITL approval and resume after the decision.
type RunState = 'idle' | 'streaming' | 'paused';

export function ChatPage({ agent, onBack }: { agent: AgentSummary; onBack: () => void }) {
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState('');
  const [runState, setRunState] = useState<RunState>('idle');
  const [pendingAction, setPendingAction] = useState<RequiredAction | null>(null);
  // One session for the whole page visit → multi-turn conversation memory on the AGM side.
  const sessionIdRef = useRef<string>(crypto.randomUUID());
  const abortRef = useRef<(() => void) | null>(null);
  const scrollRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight });
  }, [messages, runState]);

  useEffect(() => () => abortRef.current?.(), []);

  function patchLast(patch: (m: Message) => Message) {
    setMessages((prev) => {
      const next = [...prev];
      next[next.length - 1] = patch(next[next.length - 1]);
      return next;
    });
  }

  function handleChunk(chunk: StreamChunk) {
    // Event-protocol frames (the current AGM stream contract).
    switch (chunk.event) {
      case 'CONTENT_DELTA':
        patchLast((m) => ({ ...m, content: m.content + (chunk.data ?? '') }));
        return;
      case 'REASONING_DELTA':
        patchLast((m) => ({ ...m, thoughts: (m.thoughts ?? '') + (chunk.data ?? '') }));
        return;
      case 'TOOL_START':
        patchLast((m) => ({ ...m, activity: [...m.activity, `🔧 ${chunk.data ?? 'tool call'}`] }));
        return;
      case 'TOOL_END':
        patchLast((m) => ({ ...m, activity: [...m.activity, `✅ ${chunk.data ?? 'tool done'}`] }));
        return;
      case 'TOOL_ERROR':
        patchLast((m) => ({ ...m, activity: [...m.activity, `⚠️ ${chunk.data ?? 'tool error'}`] }));
        return;
      case 'PAUSED': {
        // HITL gate — parse the RequiredAction and surface the approval UI.
        let action: RequiredAction = { message: chunk.data };
        try {
          action = JSON.parse(chunk.data ?? '{}') as RequiredAction;
        } catch {
          // keep raw string as message
        }
        setPendingAction(action);
        setRunState('paused');
        return;
      }
      case 'METRICS': {
        try {
          const usage = JSON.parse(chunk.data ?? '{}') as UsageSummary;
          patchLast((m) => ({ ...m, usage }));
        } catch {
          // ignore malformed metrics
        }
        return;
      }
      case 'ERROR':
        patchLast((m) => ({ ...m, error: chunk.data ?? 'stream error' }));
        return;
      case 'STOP':
        return; // [DONE] / reader-end drives completion
    }
    // Legacy content-bearing shape.
    if (chunk.content) patchLast((m) => ({ ...m, content: m.content + chunk.content }));
  }

  function send() {
    const message = input.trim();
    if (!message || runState !== 'idle') return;
    setInput('');
    setMessages((prev) => [
      ...prev,
      { role: 'user', content: message, activity: [] },
      { role: 'assistant', content: '', activity: [] },
    ]);
    setRunState('streaming');

    abortRef.current = streamRun(
      agent.id,
      { message, stream: true, sessionId: sessionIdRef.current },
      {
        onChunk: handleChunk,
        onError: (err) => {
          patchLast((m) => ({ ...m, error: err.message }));
          setRunState('idle');
        },
        onComplete: () => setRunState((s) => (s === 'paused' ? s : 'idle')),
      },
    );
  }

  async function resolve(decision: 'APPROVED' | 'REJECTED') {
    if (!pendingAction) return;
    try {
      if (pendingAction.approvalId) {
        await approvalsApi.resolveToolApproval(pendingAction.approvalId, decision);
      } else if (pendingAction.escalationId) {
        await approvalsApi.resolveEscalation(pendingAction.escalationId, decision);
      }
      patchLast((m) => ({
        ...m,
        activity: [...m.activity, decision === 'APPROVED' ? '👍 approved — resuming' : '👎 rejected'],
      }));
    } catch (err) {
      patchLast((m) => ({ ...m, error: (err as Error).message }));
    } finally {
      setPendingAction(null);
      setRunState('idle');
    }
  }

  return (
    <div className="chat">
      <header className="chat-header">
        <button className="link" onClick={onBack}>← agents</button>
        <strong>{agent.name || agent.id}</strong>
        <span className="muted mono">session {sessionIdRef.current.slice(0, 8)}</span>
      </header>

      <div className="chat-scroll" ref={scrollRef}>
        {messages.length === 0 && (
          <p className="muted">
            Send a message to run <strong>{agent.name || agent.id}</strong>. Responses stream
            token-by-token; tool calls and HITL pauses show inline.
          </p>
        )}
        {messages.map((m, i) => (
          <div key={i} className={`bubble ${m.role}`}>
            {m.thoughts && <details><summary>thoughts</summary><pre>{m.thoughts}</pre></details>}
            {m.activity.length > 0 && (
              <div className="activity">{m.activity.map((a, j) => <div key={j}>{a}</div>)}</div>
            )}
            <div className="content">{m.content || (m.role === 'assistant' && runState === 'streaming' && i === messages.length - 1 ? '…' : '')}</div>
            {m.error && <div className="error">{m.error}</div>}
            {m.usage && (
              <div className="usage muted">
                {m.usage.totalTokens ?? '?'} tokens
                {m.usage.costUsd !== undefined && ` · $${m.usage.costUsd.toFixed(4)}`}
                {m.usage.model && ` · ${m.usage.model}`}
              </div>
            )}
          </div>
        ))}

        {runState === 'paused' && pendingAction && (
          <div className="hitl">
            <strong>⏸ Approval required</strong>
            <p>
              {pendingAction.toolName
                ? `The agent wants to run tool "${pendingAction.toolName}".`
                : pendingAction.message || 'The run paused for human approval.'}
            </p>
            <div className="hitl-actions">
              <button onClick={() => resolve('APPROVED')}>Approve</button>
              <button className="danger" onClick={() => resolve('REJECTED')}>Reject</button>
            </div>
          </div>
        )}
      </div>

      <footer className="chat-input">
        <textarea
          value={input}
          placeholder={runState === 'paused' ? 'Resolve the approval to continue…' : 'Message the agent…'}
          disabled={runState !== 'idle'}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault();
              send();
            }
          }}
        />
        <button onClick={send} disabled={runState !== 'idle' || !input.trim()}>
          {runState === 'streaming' ? 'Streaming…' : 'Send'}
        </button>
      </footer>
    </div>
  );
}
