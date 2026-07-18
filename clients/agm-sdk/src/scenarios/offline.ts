// T0 scenarios — no backend. Exercise the runner's offline path and pin the
// two pieces of client code most likely to break silently: the SSE frame
// parser and the error classifier. (Also covered by tests/*.test.ts; here they
// prove the scenario harness runs without any environment.)

import { createSseParser } from '../sdk/sse.js';
import { classifyError } from '../sdk/http.js';
import { fail, pass, type Scenario } from '../harness/scenario.js';
import type { StreamChunk } from '../types.js';

const sseRoundtrip: Scenario = {
  id: 'TC-OBS-0a',
  domain: 'F12',
  title: 'SSE parser: frames, [DONE], partial-line buffering (offline)',
  tier: 'T0',
  priority: 'P1',
  async run() {
    const chunks: StreamChunk[] = [];
    let done = 0;
    const parser = createSseParser({ onChunk: (c) => chunks.push(c), onDone: () => done++ });
    parser.push('data: {"event":"START"}\ndata: {"event":"CONTENT_');
    parser.push('DELTA","data":"hi"}\ndata: [DONE]\n');
    parser.push('data: {"event":"CONTENT_DELTA","data":"late"}\n'); // must be ignored after DONE
    parser.end();
    const events = chunks.map((c) => c.event);
    if (done !== 1) return fail(`expected onDone once, got ${done}`);
    if (events.join(',') !== 'START,CONTENT_DELTA') return fail(`unexpected events: ${events.join(',')}`);
    if (chunks[1].data !== 'hi') return fail(`bad reassembly: ${chunks[1].data}`);
    return pass('frames parsed, DONE terminal, partial line reassembled');
  },
};

const errorClassify: Scenario = {
  id: 'TC-OBS-0b',
  domain: 'F12',
  title: 'Error taxonomy classifier (offline)',
  tier: 'T0',
  priority: 'P1',
  async run() {
    const cases: Array<[number, string, string]> = [
      [401, 'Unauthorized', 'auth'],
      [400, "No API key configured for provider 'OPENAI'", 'provider-key'],
      [400, 'Concurrency limit reached', 'validation'],
      [404, 'Agent not found', 'validation'],
      [503, 'unavailable', 'server'],
    ];
    for (const [status, detail, expected] of cases) {
      const got = classifyError(status, detail);
      if (got !== expected) return fail(`classify(${status},"${detail}") = ${got}, expected ${expected}`);
    }
    return pass(`${cases.length} classifications correct`);
  },
};

export const offlineScenarios: Scenario[] = [sseRoundtrip, errorClassify];
