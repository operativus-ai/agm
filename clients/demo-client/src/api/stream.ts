// Run streaming — the AGM-specific part a naive client gets wrong.
//
// POST /api/agents/{id}/runs/stream returns Server-Sent-Event frames over a
// POST response. Browser EventSource cannot POST, so we read the body stream
// manually: split on newlines, take `data:` lines, JSON-parse each frame,
// stop on the `[DONE]` sentinel. Mirrors agent-manager-ui's ChatApi.streamMessage.

import { authHeaders, clearToken } from './client';
import type { RunRequest, StreamChunk } from '../types';

export interface StreamHandlers {
  onChunk: (chunk: StreamChunk) => void;
  onError: (error: Error) => void;
  onComplete: () => void;
}

/** Starts a streamed run. Returns an abort function. */
export function streamRun(agentId: string, request: RunRequest, handlers: StreamHandlers): () => void {
  const controller = new AbortController();

  fetch(`/api/agents/${encodeURIComponent(agentId)}/runs/stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...authHeaders(),
    },
    body: JSON.stringify(request),
    signal: controller.signal,
  })
    .then(async (response) => {
      if (response.status === 401) {
        clearToken();
        throw new Error('Unauthorized — please sign in again');
      }
      if (!response.ok) {
        // Surface the problem+json detail (e.g. "No API key configured for provider …")
        let detail = response.statusText || `HTTP ${response.status}`;
        try {
          const body = await response.json();
          detail = body?.detail || body?.message || body?.error || detail;
        } catch {
          // non-JSON body — keep status text
        }
        throw new Error(detail);
      }

      const reader = response.body?.getReader();
      if (!reader) throw new Error('No readable stream available');
      const decoder = new TextDecoder();
      let buffer = '';

      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() ?? ''; // keep the trailing partial line

        for (const line of lines) {
          if (!line.startsWith('data:')) continue;
          const data = line.slice(5).trim();
          if (!data) continue; // heartbeat
          if (data === '[DONE]') {
            handlers.onComplete();
            return;
          }
          try {
            handlers.onChunk(JSON.parse(data) as StreamChunk);
          } catch {
            // Malformed frame — skip rather than kill the stream.
          }
        }
      }
      handlers.onComplete();
    })
    .catch((err: unknown) => {
      if ((err as Error)?.name === 'AbortError') return;
      handlers.onError(err instanceof Error ? err : new Error(String(err)));
    });

  return () => controller.abort();
}
