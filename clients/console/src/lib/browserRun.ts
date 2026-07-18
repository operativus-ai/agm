// Mode B — run the @agm/sdk scenario registry in-browser against the logged-in
// identity. Adapts the harness (built for a Node CLI) to the browser: the single
// authenticated client becomes the 'admin' identity, reachability is assumed
// (login already succeeded), and results stream back to the UI instead of a file.

import {
  AgmClient,
  Budget,
  Ctx,
  allScenarios,
  detectFlags,
  poolFromClients,
  runScenarios,
  type AuthResponse,
  type ScenarioReport,
  type Tier,
} from '@agm/sdk';
import { BASE_URL } from './session';

export interface RunConfig {
  maxTier?: Tier;
  domain?: string;
  ids?: string[];
  budgetUsd?: number;
  agentIdPin?: string;
}

function runId(): string {
  return crypto.randomUUID().slice(0, 8);
}

export async function runScenariosInBrowser(
  client: AgmClient,
  auth: AuthResponse,
  cfg: RunConfig,
): Promise<{ id: string; reports: ScenarioReport[] }> {
  const id = runId();
  const anon = new AgmClient(BASE_URL);
  const pool = poolFromClients({ admin: { client, auth }, anon });
  const flags = await detectFlags(client).catch(() => ({}));
  const budget = new Budget(cfg.budgetUsd ?? 0);
  const ctx = new Ctx(id, `tc-${id}-`, BASE_URL, pool, flags, budget, cfg.agentIdPin);

  const reports = await runScenarios(allScenarios, ctx, {
    maxTier: cfg.maxTier,
    domain: cfg.domain,
    ids: cfg.ids,
    reachable: true, // login proved it
  });
  return { id, reports };
}

/** Scenario metadata for rendering the registry without running it. */
export function scenarioCatalog() {
  return allScenarios.map((s) => ({
    id: s.id,
    domain: s.domain,
    title: s.title,
    tier: s.tier,
    priority: s.priority,
  }));
}
