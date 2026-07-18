// Pure SSE frame parser — separated from fetch so it is unit-testable offline.
//
// AGM streams runs as Server-Sent-Event frames over a POST response
// (`data: <json>` lines, `data: [DONE]` sentinel, blank-line heartbeats).
// EventSource cannot POST, so consumers feed raw text chunks in here.

import type { StreamChunk } from '../types.js';

export interface SseParserEvents {
  onChunk: (chunk: StreamChunk) => void;
  onDone: () => void;
}

export interface SseParser {
  /** Feed decoded response text (any chunking — partial lines are buffered). */
  push(text: string): void;
  /** Signal reader end-of-stream (flushes nothing; completes if [DONE] not seen). */
  end(): void;
}

export function createSseParser(events: SseParserEvents): SseParser {
  let buffer = '';
  let done = false;

  function handleLine(line: string): void {
    if (done || !line.startsWith('data:')) return;
    const data = line.slice(5).trim();
    if (!data) return; // heartbeat
    if (data === '[DONE]') {
      done = true;
      events.onDone();
      return;
    }
    try {
      events.onChunk(JSON.parse(data) as StreamChunk);
    } catch {
      // Malformed frame — skip rather than kill the stream.
    }
  }

  return {
    push(text: string): void {
      buffer += text;
      const lines = buffer.split('\n');
      buffer = lines.pop() ?? ''; // retain trailing partial line
      for (const line of lines) handleLine(line);
    },
    end(): void {
      if (buffer) handleLine(buffer);
      if (!done) {
        done = true;
        events.onDone();
      }
    },
  };
}
