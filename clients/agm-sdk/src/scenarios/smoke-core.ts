// Core-contract scenarios — the 8 checks the standalone smoke.ts runs, ported
// into the scenario model so the runner drives them with uniform gating and
// reporting. Covers the P1 slice of F1/F3/F4/F12 (auth, streamed run, session,
// error taxonomy). smoke.ts stays as the dependency-free fast path.

import { AgmApiError } from '../sdk/http.js';
import { AgmClient } from '../sdk/agm.js';
import { fail, pass, skip, warn, type Scenario } from '../harness/scenario.js';

const reachable: Scenario = {
  id: 'TC-OBS-1',
  domain: 'F12',
  title: 'backend reachable',
  tier: 'T1',
  priority: 'P1',
  prereqs: { backend: true },
  async run(ctx) {
    return (await ctx.anon.isReachable())
      ? pass()
      : fail(`nothing answering at ${ctx.baseUrl}`);
  },
};

const login: Scenario = {
  id: 'TC-AUTH-1',
  domain: 'F1',
  title: 'login → JWT (POST /api/auth/login)',
  tier: 'T1',
  priority: 'P1',
  prereqs: { backend: true, admin: true },
  async run(ctx) {
    const auth = ctx.identities.admin.auth;
    if (!auth?.token) return fail('admin identity has no token');
    return pass(`user=${auth.username} roles=${auth.roles.join(',')}`, { roles: auth.roles });
  },
};

const health: Scenario = {
  id: 'TC-OBS-1b',
  domain: 'F12',
  title: 'health (GET /api/v1/health)',
  tier: 'T1',
  priority: 'P1',
  prereqs: { backend: true, admin: true },
  async run(ctx) {
    const status = await ctx.admin().healthStatus();
    if (status === 200) return pass('HTTP 200');
    return warn(`HTTP ${status}${status === 401 ? ' (secured in dev; 200 in prod via Caddy)' : ''}`);
  },
};

const discovery: Scenario = {
  id: 'TC-AGENT-1',
  domain: 'F2',
  title: 'discovery (GET /api/agents)',
  tier: 'T1',
  priority: 'P1',
  prereqs: { backend: true, admin: true },
  async run(ctx) {
    const agents = await ctx.admin().listAgents();
    const pin = ctx.agentIdPin;
    const chosen = pin ? agents.find((a) => a.id === pin) : agents[0];
    if (chosen) ctx.agentId = chosen.id; // seed downstream run scenarios
    if (!chosen) return warn(pin ? `agent '${pin}' not found` : 'no agents registered');
    return pass(`${agents.length} agents; using '${chosen.id}'`, { count: agents.length, agentId: chosen.id });
  },
};

const streamedRun: Scenario = {
  id: 'TC-RUN-1',
  domain: 'F3',
  title: 'streamed run (SSE over POST)',
  tier: 'T2',
  priority: 'P1',
  prereqs: { backend: true, admin: true, agent: true, providerKey: true },
  async run(ctx) {
    const sessionId = crypto.randomUUID();
    ctx.track(async () => {
      await ctx.admin().http.delete(`/sessions/${sessionId}`).catch(() => {});
    });
    const r = await ctx.admin().run(ctx.agentId!, {
      message: 'Reply with the single word: pong',
      stream: true,
      sessionId,
    });
    ctx.budget.record(r.usage);
    if (r.paused) return warn(`run PAUSED for HITL (${r.paused.toolName ?? r.paused.type ?? 'approval'})`);
    if (r.streamError) return fail(`ERROR frame: ${r.streamError}`);
    if (r.text.trim().length === 0) return warn(`empty content (events: ${r.events.join(',') || 'none'})`);
    return pass(`${r.events.length} events, ${r.text.length} chars`, {
      events: r.events.length,
      chars: r.text.length,
      usage: r.usage,
    });
  },
};

const errorTaxonomyAuth: Scenario = {
  id: 'TC-AUTH-2',
  domain: 'F1',
  title: 'error taxonomy: 401 on a bad token',
  tier: 'T1',
  priority: 'P1',
  prereqs: { backend: true },
  async run(ctx) {
    const rogue = new AgmClient(ctx.baseUrl);
    rogue.setToken('not-a-real-jwt');
    try {
      await rogue.listAgents();
      return fail('expected 401, got success');
    } catch (err) {
      return err instanceof AgmApiError && err.kind === 'auth'
        ? pass('401 → kind=auth')
        : fail(`unexpected: ${(err as Error).message}`);
    }
  },
};

const errorTaxonomyUnknownAgent: Scenario = {
  id: 'TC-RUN-1b',
  domain: 'F3',
  title: 'error taxonomy: stream to unknown agent rejects (no hang)',
  tier: 'T1',
  priority: 'P1',
  prereqs: { backend: true, admin: true },
  async run(ctx) {
    try {
      await ctx.admin().run('no-such-agent-xyz', { message: 'hi', stream: true }, { timeoutMs: 15_000 });
      return fail('expected rejection, got completion');
    } catch (err) {
      const ok = err instanceof AgmApiError && (err.kind === 'validation' || err.status === 404);
      return ok ? pass(`rejected: ${(err as AgmApiError).status}`) : warn((err as Error).message);
    }
  },
};

const multiTurn: Scenario = {
  id: 'TC-SESS-1',
  domain: 'F4',
  title: 'multi-turn + session replay (same sessionId)',
  tier: 'T2',
  priority: 'P1',
  prereqs: { backend: true, admin: true, agent: true, providerKey: true },
  async run(ctx) {
    const sessionId = crypto.randomUUID();
    ctx.track(async () => {
      await ctx.admin().http.delete(`/sessions/${sessionId}`).catch(() => {});
    });
    const r1 = await ctx.admin().run(ctx.agentId!, { message: 'Remember the number 7. Reply: ok', stream: true, sessionId });
    ctx.budget.record(r1.usage);
    if (r1.paused || r1.streamError) return skip('first turn paused/errored');
    const r2 = await ctx.admin().run(ctx.agentId!, { message: 'What number did I ask you to remember?', stream: true, sessionId });
    ctx.budget.record(r2.usage);
    const runs = await ctx.admin().sessionRuns(sessionId);
    if (runs.length < 2) return warn(`only ${runs.length} runs recorded`);
    const recalled = r2.text.includes('7');
    return recalled
      ? pass(`${runs.length} runs; turn 2 recalled the fact`, { runs: runs.length })
      : warn(`${runs.length} runs recorded but recall not detected (model-dependent)`, { runs: runs.length });
  },
};

export const smokeCoreScenarios: Scenario[] = [
  reachable,
  login,
  health,
  discovery,
  streamedRun,
  errorTaxonomyAuth,
  errorTaxonomyUnknownAgent,
  multiTurn,
];
