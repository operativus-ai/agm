// Run streaming + the collector harness.
//
// collectRun() drives POST /api/agents/{id}/runs/stream to completion and
// returns everything a test wants to assert on: ordered events, concatenated
// text, the HITL RequiredAction if the run PAUSED, usage metrics, and errors —
// so test code reads `result.text` / `result.paused` instead of re-implementing
// frame plumbing.

import { AgmApiError, classifyError, problemDetail, type HttpClient } from './http.js';
import { createSseParser } from './sse.js';
import type {
  RequiredAction,
  RunRequest,
  StreamChunk,
  StreamEvent,
  UsageSummary,
} from '../types.js';

/** Incremental streaming handlers — for a live UI that renders frames as they arrive. */
export interface StreamHandlers {
  onChunk: (chunk: StreamChunk) => void;
  onError: (error: Error) => void;
  onComplete: () => void;
}

/**
 * Drive POST /api/agents/{id}/runs/stream and deliver each SSE frame to
 * handlers.onChunk as it arrives (unlike collectRun, which returns only at the
 * end). Returns an abort function. Used by the console for live token rendering.
 */
export function streamRun(
  http: HttpClient,
  agentId: string,
  request: RunRequest,
  handlers: StreamHandlers,
): () => void {
  const controller = new AbortController();

  fetch(`${http.baseUrl}/api/agents/${encodeURIComponent(agentId)}/runs/stream`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream', ...http.authHeaders() },
    body: JSON.stringify(request),
    signal: controller.signal,
  })
    .then(async (response) => {
      if (!response.ok) {
        const body = await response.json().catch(() => undefined);
        const detail = problemDetail(body, response.statusText || `HTTP ${response.status}`);
        throw new AgmApiError(detail, response.status, classifyError(response.status, detail), body);
      }
      const reader = response.body?.getReader();
      if (!reader) throw new Error('No readable stream available');
      const decoder = new TextDecoder();
      let done = false;
      const parser = createSseParser({ onChunk: handlers.onChunk, onDone: () => { done = true; } });
      for (;;) {
        const { done: end, value } = await reader.read();
        if (end) break;
        parser.push(decoder.decode(value, { stream: true }));
        if (done) break;
      }
      parser.end();
      handlers.onComplete();
    })
    .catch((err: unknown) => {
      if ((err as Error)?.name === 'AbortError') return;
      handlers.onError(err instanceof Error ? err : new Error(String(err)));
    });

  return () => controller.abort();
}

export interface RunResult {
  /** Every parsed frame, in arrival order. */
  frames: StreamChunk[];
  /** Ordered event names (event-protocol frames only). */
  events: StreamEvent[];
  /** Concatenated CONTENT_DELTA payloads (+ legacy `content` frames). */
  text: string;
  /** Concatenated REASONING_DELTA payloads. */
  reasoning: string;
  /** Set iff a PAUSED frame arrived — the HITL required action. */
  paused?: RequiredAction;
  /** Parsed METRICS frame (terminal usage summary), if streamed. */
  usage?: UsageSummary;
  /** Payload of an ERROR frame, if any. */
  streamError?: string;
  /** True when the [DONE] sentinel (or clean reader end) was reached. */
  completed: boolean;
}

export interface CollectOptions {
  /** Abort the run if it exceeds this (default 120s). */
  timeoutMs?: number;
}

export async function collectRun(
  http: HttpClient,
  agentId: string,
  request: RunRequest,
  options: CollectOptions = {},
): Promise<RunResult> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), options.timeoutMs ?? 120_000);

  const result: RunResult = {
    frames: [],
    events: [],
    text: '',
    reasoning: '',
    completed: false,
  };

  try {
    const response = await fetch(`${http.baseUrl}/api/agents/${encodeURIComponent(agentId)}/runs/stream`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
        ...http.authHeaders(),
      },
      body: JSON.stringify(request),
      signal: controller.signal,
    });

    if (!response.ok) {
      const body = await response.json().catch(() => undefined);
      const detail = problemDetail(body, response.statusText || `HTTP ${response.status}`);
      throw new AgmApiError(detail, response.status, classifyError(response.status, detail), body);
    }

    const reader = response.body?.getReader();
    if (!reader) throw new Error('No readable stream available');
    const decoder = new TextDecoder();

    let sawDone = false;
    const parser = createSseParser({
      onChunk(chunk) {
        result.frames.push(chunk);
        if (chunk.event) result.events.push(chunk.event);
        switch (chunk.event) {
          case 'CONTENT_DELTA':
            result.text += chunk.data ?? '';
            break;
          case 'REASONING_DELTA':
            result.reasoning += chunk.data ?? '';
            break;
          case 'PAUSED':
            result.paused = parseJsonOr<RequiredAction>(chunk.data, { message: chunk.data });
            break;
          case 'METRICS':
            result.usage = parseJsonOr<UsageSummary>(chunk.data, undefined);
            break;
          case 'ERROR':
            result.streamError = chunk.data ?? 'stream error';
            break;
          default:
            if (!chunk.event && chunk.content) result.text += chunk.content; // legacy shape
        }
      },
      onDone() {
        sawDone = true;
      },
    });

    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      parser.push(decoder.decode(value, { stream: true }));
      if (sawDone) break;
    }
    parser.end();
    result.completed = true;
    return result;
  } finally {
    clearTimeout(timeout);
    controller.abort();
  }
}

function parseJsonOr<T>(data: string | undefined, fallback: T | undefined): T | undefined {
  if (!data) return fallback;
  try {
    return JSON.parse(data) as T;
  } catch {
    return fallback;
  }
}
