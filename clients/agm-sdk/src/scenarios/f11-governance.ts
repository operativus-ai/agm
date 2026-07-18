// F11 — Governance. TC-GOV-1 (P1 half): provider-credentials endpoint reachable
// + shape. The destructive add→run→remove→400 transition is deferred to Phase 5
// (it must run in an isolated org so it can't clobber a shared dev env's live key).

import { fail, pass, type Scenario } from '../harness/scenario.js';

const providerCredentials: Scenario = {
  id: 'TC-GOV-1',
  domain: 'F11',
  title: 'provider credentials list (GET /api/v1/provider-credentials)',
  tier: 'T1',
  priority: 'P1',
  prereqs: { backend: true, admin: true },
  async run(ctx) {
    const creds = await ctx.admin().listProviderCredentials();
    if (!Array.isArray(creds)) return fail('provider-credentials list is not an array');
    const shaped = creds.every((c) => typeof c.provider === 'string' && typeof c.id === 'string');
    if (!shaped && creds.length > 0) return fail('provider-credential rows missing id/provider');
    const providers = creds.map((c) => c.provider);
    return pass(
      creds.length === 0
        ? 'endpoint OK; no credentials configured (LLM runs will 400 until one is added)'
        : `${creds.length} credential(s): ${providers.join(', ')}`,
      { count: creds.length, providers },
    );
  },
};

export const governanceScenarios: Scenario[] = [providerCredentials];
