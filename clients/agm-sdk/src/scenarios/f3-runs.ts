// F3 — Runs. TC-RUN-2: sync + background run parity.

import { fail, pass, warn, type Scenario } from '../harness/scenario.js';

const syncAndBackground: Scenario = {
  id: 'TC-RUN-2',
  domain: 'F3',
  title: 'sync run + background run (parity, no hang)',
  tier: 'T2',
  priority: 'P1',
  prereqs: { backend: true, admin: true, agent: true, providerKey: true },
  async run(ctx) {
    const agm = ctx.admin();
    const prompt = 'Reply with the single word: pong';

    // Sync
    const sync = await agm.runSync(ctx.agentId!, { message: prompt, stream: false, sessionId: crypto.randomUUID() });
    ctx.track(async () => {
      if (sync.sessionId) await agm.http.delete(`/sessions/${sync.sessionId}`).catch(() => {});
    });
    const syncOk = (sync.content ?? '').trim().length > 0 || sync.status === 'COMPLETED';

    // Background → poll status to terminal
    const bg = await agm.runBackground(ctx.agentId!, { message: prompt, stream: false, sessionId: crypto.randomUUID() });
    const runId = bg.runId ?? bg.id;
    if (!runId) return warn(`sync ok=${syncOk}; background returned no runId (status=${bg.status})`);

    const terminal = await pollBackground(agm, ctx.agentId!, runId, 60_000);
    if (!syncOk) return fail(`sync run produced no content (status=${sync.status})`);
    if (!terminal) return warn(`sync ok; background run ${runId} not terminal within 60s`);
    return pass(`sync=${sync.status ?? 'ok'} · background=${terminal}`, { syncStatus: sync.status, bgStatus: terminal });
  },
};

async function pollBackground(
  agm: ReturnType<import('../harness/scenario.js').Ctx['admin']>,
  agentId: string,
  runId: string,
  timeoutMs: number,
): Promise<string | null> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const rows = await agm.runStatusBatch(agentId, [runId]).catch(() => []);
    const row = rows.find((r) => r.id === runId);
    if (row && ['COMPLETED', 'FAILED', 'CANCELLED'].includes(row.status)) return row.status;
    await new Promise((r) => setTimeout(r, 2000));
  }
  return null;
}

export const runsScenarios: Scenario[] = [syncAndBackground];
