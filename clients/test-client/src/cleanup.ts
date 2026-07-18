// `npm run cleanup` — sweep orphaned harness fixtures (tc-*) left by a crashed
// run. Requires an admin identity. Optionally scope to one run: --run=<id>.

import { config } from './config.js';
import { resolveIdentities, sweepAll } from '@agm/sdk';

async function main(): Promise<void> {
  const runArg = process.argv.slice(2).find((a) => a.startsWith('--run='));
  const runId = runArg?.slice(6);

  const identities = await resolveIdentities(config.baseUrl, config.identities);
  if (!identities.admin.available) {
    console.error(`cleanup needs an admin identity: ${identities.admin.reason ?? 'not configured'}`);
    process.exit(1);
  }

  console.log(`Sweeping harness fixtures${runId ? ` for run ${runId}` : ' (all tc-*)'} on ${config.baseUrl}\n`);
  const results = await sweepAll(identities.admin.client, runId);
  for (const r of results) {
    console.log(`${r.resource}: ${r.deleted.length} deleted${r.errors.length ? `, ${r.errors.length} errors` : ''}`);
    r.errors.forEach((e) => console.log(`  ⚠️  ${e}`));
  }
  process.exit(0);
}

main().catch((err: unknown) => {
  console.error('cleanup crashed:', err);
  process.exit(1);
});
