// End-to-end smoke run against a live AGM backend.
//
//   set -a; source .env; set +a; npm run smoke
//
// Exercises the client-contract points from clients/docs/analysis/demo-what-is-a-client.md:
// auth → discovery → streamed run → multi-turn session + replay → error taxonomy.
// A missing provider key is reported as WARN (environment gap), never FAIL.

import { AgmClient, AgmApiError, type AgentSummary } from '@agm/sdk';
import { config, requireCreds } from './config.js';

type Outcome = 'PASS' | 'FAIL' | 'WARN' | 'SKIP';
const results: Array<{ step: string; outcome: Outcome; note?: string }> = [];

function record(step: string, outcome: Outcome, note?: string): void {
  const icon = { PASS: '✅', FAIL: '❌', WARN: '⚠️ ', SKIP: '⏭️ ' }[outcome];
  console.log(`${icon} ${step}${note ? ` — ${note}` : ''}`);
  results.push({ step, outcome, note });
}

async function main(): Promise<void> {
  console.log(`AGM smoke — ${config.baseUrl}\n`);
  const agm = new AgmClient(config.baseUrl);

  // 0. Reachability (any HTTP response proves liveness; only network errors fail)
  if (!(await agm.isReachable())) {
    record('backend reachable', 'FAIL', `nothing answering at ${config.baseUrl} — boot the BE first`);
    return finish();
  }
  record('backend reachable', 'PASS');

  // 1. Auth
  try {
    const { username, password } = requireCreds();
    const auth = await agm.login(username, password);
    record('login (POST /api/auth/login)', 'PASS', `user=${auth.username} roles=${auth.roles.join(',')}`);
  } catch (err) {
    record('login (POST /api/auth/login)', 'FAIL', (err as Error).message);
    return finish();
  }

  // 2. Health endpoint (may 401 under the dev profile — still proves the route exists)
  const healthStatus = await agm.healthStatus();
  record(
    'health (GET /api/v1/health)',
    healthStatus === 200 ? 'PASS' : 'WARN',
    `HTTP ${healthStatus}${healthStatus === 401 ? ' (secured in dev — 200 expected in prod via Caddy)' : ''}`,
  );

  // 3. Discovery
  let agent: AgentSummary | undefined;
  try {
    const agents = await agm.listAgents();
    agent = config.agentId ? agents.find((a) => a.id === config.agentId) : agents[0];
    if (!agent) {
      record('discovery (GET /api/agents)', 'WARN', config.agentId ? `agent '${config.agentId}' not found` : 'no agents registered');
    } else {
      record('discovery (GET /api/agents)', 'PASS', `${agents.length} agents; using '${agent.id}'`);
    }
  } catch (err) {
    record('discovery (GET /api/agents)', 'FAIL', (err as Error).message);
  }

  // 4. Streamed run + 5. multi-turn + 6. replay
  if (agent) {
    const sessionId = crypto.randomUUID();
    let firstRunOk = false;
    try {
      const r1 = await agm.run(agent.id, {
        message: 'Reply with the single word: pong',
        stream: true,
        sessionId,
      });
      if (r1.paused) {
        record('streamed run (SSE over POST)', 'WARN', `run PAUSED for HITL (${r1.paused.toolName ?? r1.paused.type ?? 'approval'}) — resolve via /v1/approvals`);
      } else if (r1.streamError) {
        record('streamed run (SSE over POST)', 'FAIL', `ERROR frame: ${r1.streamError}`);
      } else if (r1.text.trim().length > 0) {
        firstRunOk = true;
        const usage = r1.usage ? ` · ${r1.usage.totalTokens ?? '?'} tokens${r1.usage.costUsd !== undefined ? ` $${r1.usage.costUsd.toFixed(4)}` : ''}` : '';
        record('streamed run (SSE over POST)', 'PASS', `${r1.events.length} events, ${r1.text.length} chars${usage}`);
      } else {
        record('streamed run (SSE over POST)', 'WARN', `completed but empty content (events: ${r1.events.join(',') || 'none'})`);
      }
    } catch (err) {
      if (err instanceof AgmApiError && err.kind === 'provider-key') {
        record('streamed run (SSE over POST)', 'WARN', `${err.message} — environment gap: add a credential via POST /api/v1/provider-credentials`);
      } else {
        record('streamed run (SSE over POST)', 'FAIL', (err as Error).message);
      }
    }

    if (firstRunOk) {
      try {
        const r2 = await agm.run(agent.id, { message: 'And now reply: pong2', stream: true, sessionId });
        record('multi-turn (same sessionId)', r2.completed && !r2.streamError ? 'PASS' : 'WARN', `${r2.text.length} chars`);
        const runs = await agm.sessionRuns(sessionId);
        record(
          'session replay (GET /sessions/{id}/runs)',
          runs.length >= 2 ? 'PASS' : 'WARN',
          `${runs.length} runs recorded`,
        );
      } catch (err) {
        record('multi-turn / replay', 'FAIL', (err as Error).message);
      }
    } else {
      record('multi-turn (same sessionId)', 'SKIP', 'first run did not complete');
      record('session replay (GET /sessions/{id}/runs)', 'SKIP', 'first run did not complete');
    }
  }

  // 7. Error taxonomy — 401 on a garbage token
  try {
    const rogue = new AgmClient(config.baseUrl);
    rogue.setToken('not-a-real-jwt');
    await rogue.listAgents();
    record('error taxonomy (401 on bad token)', 'FAIL', 'expected 401, got success');
  } catch (err) {
    const ok = err instanceof AgmApiError && err.kind === 'auth';
    record('error taxonomy (401 on bad token)', ok ? 'PASS' : 'FAIL', ok ? undefined : (err as Error).message);
  }

  // 8. Error taxonomy — streaming to a nonexistent agent must reject, not hang
  try {
    await agm.run('no-such-agent-xyz', { message: 'hi', stream: true }, { timeoutMs: 15_000 });
    record('error taxonomy (unknown agent)', 'FAIL', 'expected rejection, got completion');
  } catch (err) {
    const ok = err instanceof AgmApiError && (err.kind === 'validation' || err.status === 404);
    record('error taxonomy (unknown agent)', ok ? 'PASS' : 'WARN', (err as Error).message);
  }

  finish();
}

function finish(): void {
  const counts = { PASS: 0, FAIL: 0, WARN: 0, SKIP: 0 };
  for (const r of results) counts[r.outcome]++;
  console.log(`\n${counts.PASS} pass · ${counts.FAIL} fail · ${counts.WARN} warn · ${counts.SKIP} skip`);
  process.exit(counts.FAIL > 0 ? 1 : 0);
}

main().catch((err: unknown) => {
  console.error('smoke crashed:', err);
  process.exit(1);
});
