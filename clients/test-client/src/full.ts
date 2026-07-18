// Full-feature scenario runner (Phase 0 foundation).
//
//   set -a; source .env; set +a
//   npm run full                 # run everything the environment allows
//   npm run full -- --dry-run    # list discovered scenarios + gating, run nothing
//   npm run full -- --tier=T0    # offline only
//   npm run full -- --tier=T1    # offline + live-no-LLM
//   npm run full -- --domain=F3  # one domain
//   npm run full -- --id=TC-AUTH-1,TC-RUN-1
//
// Phases 1–5 add scenario files under src/scenarios/; this entrypoint is stable.

import { config } from './config.js';
import {
  AgmClient,
  Budget,
  Ctx,
  allScenarios,
  describeFlags,
  detectFlags,
  resolveIdentities,
  runPrefix,
  runScenarios,
  type Tier,
} from '@agm/sdk';
import { exitCode, printSummary, summarize, writeReport } from './report.js';

interface Args {
  tier?: Tier;
  domain?: string;
  ids?: string[];
  dryRun: boolean;
}

function parseArgs(argv: string[]): Args {
  const args: Args = { dryRun: false };
  for (const a of argv) {
    if (a === '--dry-run') args.dryRun = true;
    else if (a.startsWith('--tier=')) args.tier = a.slice(7) as Tier;
    else if (a.startsWith('--domain=')) args.domain = a.slice(9);
    else if (a.startsWith('--id=')) args.ids = a.slice(5).split(',').map((s) => s.trim()).filter(Boolean);
  }
  return args;
}

/** Short, filesystem-safe run id (no Date/random needed for correctness). */
function makeRunId(): string {
  return crypto.randomUUID().slice(0, 8);
}

async function main(): Promise<void> {
  const args = parseArgs(process.argv.slice(2));
  const runId = makeRunId();
  const startedAt = new Date().toISOString();
  console.log(`AGM full-feature harness — ${config.baseUrl}  (run ${runId})`);
  if (args.tier || args.domain || args.ids) {
    console.log(`filters: ${[args.tier && `tier<=${args.tier}`, args.domain, args.ids && `ids=${args.ids.join(',')}`].filter(Boolean).join(' ')}`);
  }

  // Reachability + identities + flags (skip the network in a T0-only dry context).
  const probe = new AgmClient(config.baseUrl);
  const reachable = await probe.isReachable();
  const identities = reachable ? await resolveIdentities(config.baseUrl, config.identities) : emptyIdentities();
  const flags =
    reachable && identities.admin.available
      ? await detectFlags(identities.admin.client, config.flagOverrides)
      : {};

  console.log(
    `env: reachable=${reachable} · admin=${identities.admin.available} userA=${identities.userA.available} userB=${identities.userB.available}`,
  );
  if (!identities.admin.available && identities.admin.reason) console.log(`     admin: ${identities.admin.reason}`);
  if (reachable) console.log(`flags: ${describeFlags(flags)}`);
  console.log('');

  const budget = new Budget(config.budgetUsd);
  const ctx = new Ctx(runId, runPrefix(runId), config.baseUrl, identities, flags, budget, config.agentId);

  const reports = await runScenarios(allScenarios, ctx, {
    maxTier: args.tier,
    domain: args.domain,
    ids: args.ids,
    reachable,
    dryRun: args.dryRun,
  });

  const summary = summarize(runId, config.baseUrl, startedAt, budget.summary(), reports);
  printSummary(summary);
  if (!args.dryRun) {
    const path = writeReport(summary);
    console.log(`report: ${path}`);
  }
  process.exit(exitCode(summary));
}

function emptyIdentities() {
  const anon = new AgmClient(config.baseUrl);
  const na = (label: string) => ({ label, client: new AgmClient(config.baseUrl), available: false, reason: 'backend not reachable' });
  return { admin: na('admin'), userA: na('userA'), userB: na('userB'), anon };
}

main().catch((err: unknown) => {
  console.error('harness crashed:', err);
  process.exit(1);
});
