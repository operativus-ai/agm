// Offline unit tests for the SSE frame parser — no backend required.
// These pin the stream-protocol handling that every AGM client must get right:
// data:-prefixed JSON frames, [DONE] sentinel, heartbeats, partial-line buffering.

import { describe, expect, it } from 'vitest';
import { createSseParser, type StreamChunk } from '@agm/sdk';

function collect(): { chunks: StreamChunk[]; doneCount: () => number; parser: ReturnType<typeof createSseParser> } {
  const chunks: StreamChunk[] = [];
  let done = 0;
  const parser = createSseParser({
    onChunk: (c) => chunks.push(c),
    onDone: () => done++,
  });
  return { chunks, doneCount: () => done, parser };
}

describe('createSseParser', () => {
  it('parses data: frames into chunks', () => {
    const { chunks, parser } = collect();
    parser.push('data: {"event":"START"}\n');
    parser.push('data: {"event":"CONTENT_DELTA","data":"hel"}\n');
    parser.push('data: {"event":"CONTENT_DELTA","data":"lo"}\n');
    expect(chunks.map((c) => c.event)).toEqual(['START', 'CONTENT_DELTA', 'CONTENT_DELTA']);
    expect(chunks[1].data).toBe('hel');
  });

  it('buffers partial lines across network chunks', () => {
    const { chunks, parser } = collect();
    parser.push('data: {"event":"CONTENT_');
    parser.push('DELTA","data":"split across packets"}\n');
    expect(chunks).toHaveLength(1);
    expect(chunks[0].data).toBe('split across packets');
  });

  it('fires onDone exactly once for the [DONE] sentinel and ignores later frames', () => {
    const { chunks, doneCount, parser } = collect();
    parser.push('data: {"event":"STOP"}\ndata: [DONE]\n');
    parser.push('data: {"event":"CONTENT_DELTA","data":"late"}\n');
    parser.end();
    expect(doneCount()).toBe(1);
    expect(chunks.map((c) => c.event)).toEqual(['STOP']);
  });

  it('skips heartbeats, non-data lines, and malformed JSON without dying', () => {
    const { chunks, doneCount, parser } = collect();
    parser.push('\n\n: comment\nevent: ping\ndata: \ndata: {not json}\ndata: {"event":"START"}\n');
    parser.end();
    expect(chunks.map((c) => c.event)).toEqual(['START']);
    expect(doneCount()).toBe(1); // reader end still completes
  });

  it('end() flushes a trailing unterminated frame', () => {
    const { chunks, parser } = collect();
    parser.push('data: {"event":"CONTENT_DELTA","data":"no trailing newline"}');
    expect(chunks).toHaveLength(0); // still buffered
    parser.end();
    expect(chunks).toHaveLength(1);
  });

  it('handles legacy content-bearing frames', () => {
    const { chunks, parser } = collect();
    parser.push('data: {"content":"legacy","type":"content"}\n');
    expect(chunks[0].content).toBe('legacy');
    expect(chunks[0].event).toBeUndefined();
  });
});
