// Proves the scenario harness itself: the runner drives T0 scenarios offline
// (no backend) and reports correctly, and gating SKIPs live scenarios when the
// environment is absent. This is the CI-mode wrapper the plan calls for — the
// same scenarios the `full` CLI runs, asserted under vitest.

import { describe, expect, it } from 'vitest';
import { AgmClient, Budget, Ctx, allScenarios, offlineScenarios, runScenarios } from '@agm/sdk';

function offlineCtx(): Ctx {
  const anon = new AgmClient('http://localhost:8080');
  const na = (label: string) => ({ label, client: anon, available: false, reason: 'test' });
  const pool = { admin: na('admin'), userA: na('userA'), userB: na('userB'), anon };
  return new Ctx('test', 'tc-test-', 'http://localhost:8080', pool, {}, new Budget(0));
}

describe('scenario harness (offline)', () => {
  it('runs all T0 scenarios to PASS with no backend', async () => {
    const reports = await runScenarios(offlineScenarios, offlineCtx(), { reachable: false });
    expect(reports.length).toBe(offlineScenarios.length);
    expect(reports.every((r) => r.outcome === 'PASS')).toBe(true);
  });

  it('SKIPs live scenarios (not FAIL) when the backend is unreachable', async () => {
    const reports = await runScenarios(allScenarios, offlineCtx(), { reachable: false });
    const live = reports.filter((r) => r.tier !== 'T0');
    expect(live.length).toBeGreaterThan(0);
    expect(live.every((r) => r.outcome === 'SKIP')).toBe(true);
    expect(reports.some((r) => r.outcome === 'FAIL')).toBe(false);
  });

  it('dry-run marks everything SKIP and runs nothing', async () => {
    const reports = await runScenarios(offlineScenarios, offlineCtx(), { reachable: true, dryRun: true });
    expect(reports.every((r) => r.outcome === 'SKIP' && r.note === 'dry-run')).toBe(true);
  });

  it('every scenario id is unique', () => {
    const ids = allScenarios.map((s) => s.id);
    expect(new Set(ids).size).toBe(ids.length);
  });
});
