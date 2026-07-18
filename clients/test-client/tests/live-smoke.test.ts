// Live contract tests against a running AGM backend. Gated: set AGM_LIVE=1
// plus AGM_USERNAME/AGM_PASSWORD (see .env.example). Without the gate the
// whole suite is skipped so `npm test` stays green offline/in CI.

import { beforeAll, describe, expect, it } from 'vitest';
import { AgmClient, AgmApiError } from '@agm/sdk';
import { config, requireCreds } from '../src/config.js';

describe.skipIf(!config.live)('live AGM contract', () => {
  const agm = new AgmClient(config.baseUrl);
  let agentId: string | undefined;

  beforeAll(async () => {
    expect(await agm.isReachable(), `backend not reachable at ${config.baseUrl}`).toBe(true);
    const { username, password } = requireCreds();
    const auth = await agm.login(username, password);
    expect(auth.token).toBeTruthy();
    const agents = await agm.listAgents();
    agentId = config.agentId ?? agents[0]?.id;
  }, 30_000);

  it('rejects a garbage token with 401/auth', async () => {
    const rogue = new AgmClient(config.baseUrl);
    rogue.setToken('garbage');
    await expect(rogue.listAgents()).rejects.toSatisfy(
      (e: unknown) => e instanceof AgmApiError && e.kind === 'auth',
    );
  });

  it('streams a run to completion (or classifies the provider-key gap)', async () => {
    if (!agentId) return; // no agents registered — covered by smoke WARN
    try {
      const result = await agm.run(agentId, {
        message: 'Reply with the single word: pong',
        stream: true,
        sessionId: crypto.randomUUID(),
      });
      expect(result.completed).toBe(true);
      // A paused run is a legitimate outcome (HITL-gated agent), not a failure.
      if (!result.paused) {
        expect(result.streamError).toBeUndefined();
        expect(result.text.length).toBeGreaterThan(0);
      }
    } catch (err) {
      // Only the provider-key environment gap is acceptable here.
      expect(err).toSatisfy((e: unknown) => e instanceof AgmApiError && e.kind === 'provider-key');
    }
  }, 120_000);

  it('records multi-turn runs on one session and replays them', async () => {
    if (!agentId) return;
    const sessionId = crypto.randomUUID();
    try {
      await agm.run(agentId, { message: 'say a', stream: true, sessionId });
      await agm.run(agentId, { message: 'say b', stream: true, sessionId });
    } catch (err) {
      if (err instanceof AgmApiError && err.kind === 'provider-key') return; // env gap
      throw err;
    }
    const runs = await agm.sessionRuns(sessionId);
    expect(runs.length).toBeGreaterThanOrEqual(2);
  }, 240_000);

  it('rejects a stream to a nonexistent agent instead of hanging', async () => {
    await expect(
      agm.run('no-such-agent-xyz', { message: 'hi', stream: true }, { timeoutMs: 15_000 }),
    ).rejects.toBeTruthy();
  }, 20_000);
});
