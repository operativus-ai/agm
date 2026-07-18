// Reporting (NFR-6): a JSON artifact for CI + a human console summary.

import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import type { Outcome, ScenarioReport } from '@agm/sdk';

export interface RunSummary {
  runId: string;
  baseUrl: string;
  startedAt: string;
  counts: Record<Outcome, number>;
  budget: string;
  scenarios: ScenarioReport[];
}

export function summarize(
  runId: string,
  baseUrl: string,
  startedAt: string,
  budget: string,
  scenarios: ScenarioReport[],
): RunSummary {
  const counts: Record<Outcome, number> = { PASS: 0, FAIL: 0, WARN: 0, SKIP: 0 };
  for (const s of scenarios) counts[s.outcome]++;
  return { runId, baseUrl, startedAt, counts, budget, scenarios };
}

/** Write the JSON artifact under reports/ (gitignored). Returns the path. */
export function writeReport(summary: RunSummary): string {
  const here = dirname(fileURLToPath(import.meta.url));
  const dir = resolve(here, '../../reports');
  mkdirSync(dir, { recursive: true });
  const path = resolve(dir, `report-${summary.runId}.json`);
  writeFileSync(path, JSON.stringify(summary, null, 2));
  return path;
}

export function printSummary(summary: RunSummary): void {
  const { counts } = summary;
  console.log('\n' + '─'.repeat(60));
  console.log(
    `${counts.PASS} pass · ${counts.FAIL} fail · ${counts.WARN} warn · ${counts.SKIP} skip` +
      `   (${summary.scenarios.length} scenarios)`,
  );
  console.log(`budget: ${summary.budget}`);
  if (counts.FAIL > 0) {
    console.log('\nFAILURES:');
    for (const s of summary.scenarios.filter((x) => x.outcome === 'FAIL')) {
      console.log(`  ❌ ${s.id} — ${s.note ?? 'no detail'}`);
    }
  }
}

/** Process exit code: 1 iff any scenario FAILED. */
export function exitCode(summary: RunSummary): number {
  return summary.counts.FAIL > 0 ? 1 : 0;
}
