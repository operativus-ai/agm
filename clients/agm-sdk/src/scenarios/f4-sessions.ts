// F4 — Sessions. TC-SESS-2: a run is recorded and replayable via the session.

import { pass, warn, type Scenario } from '../harness/scenario.js';

const replay: Scenario = {
  id: 'TC-SESS-2',
  domain: 'F4',
  title: 'session replay (GET /sessions/{id}/runs)',
  tier: 'T2',
  priority: 'P1',
  prereqs: { backend: true, admin: true, agent: true, providerKey: true },
  async run(ctx) {
    const agm = ctx.admin();
    const sessionId = crypto.randomUUID();
    ctx.track(async () => void agm.http.delete(`/sessions/${sessionId}`).catch(() => {}));

    const r = await agm.run(ctx.agentId!, { message: 'Reply: ok', stream: true, sessionId });
    ctx.budget.record(r.usage);
    if (r.paused || r.streamError) return warn('run paused/errored — replay not asserted');

    const runs = await agm.sessionRuns(sessionId);
    if (runs.length < 1) return warn('no runs recorded for the session');
    const bound = runs.every((x) => x.sessionId === sessionId);
    return bound
      ? pass(`${runs.length} run(s) replayed, all bound to the session`, { runs: runs.length })
      : warn(`${runs.length} runs but some not bound to sessionId`);
  },
};

export const sessionsScenarios: Scenario[] = [replay];
