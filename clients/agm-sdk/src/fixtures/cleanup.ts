// Fixture cleanup (NFR-3). Two mechanisms:
//  1. ctx.track(teardown) — the primary path; each fixture registers its own
//     teardown and the runner runs them after the scenario (used from Phase 1 on).
//  2. sweepByPrefix — a belt-and-braces sweep of orphaned resources whose name
//     carries the run prefix, for crash recovery / the `npm run cleanup` command.
//
// Phase 0 wires the mechanism and implements the agent sweeper (the only
// create-endpoint the SDK exposes today: DELETE /api/admin/agents/{id}). More
// resource sweepers (KB, workflow, session) land with their Phase 1–5 fixtures.

import type { AgmClient } from '../sdk/agm.js';

export const PREFIX_ROOT = 'tc-';

export function runPrefix(runId: string): string {
  return `${PREFIX_ROOT}${runId}-`;
}

/** Any name starting with tc- is a harness fixture; used by the broad sweep. */
export function isFixtureName(name: string | undefined): boolean {
  return !!name && name.startsWith(PREFIX_ROOT);
}

export interface SweepResult {
  resource: string;
  deleted: string[];
  errors: string[];
}

/** Delete tc-* agents (optionally only a specific run's). Admin identity required. */
export async function sweepAgents(admin: AgmClient, runId?: string): Promise<SweepResult> {
  const result: SweepResult = { resource: 'agents', deleted: [], errors: [] };
  const match = runId ? runPrefix(runId) : PREFIX_ROOT;
  try {
    const agents = await admin.listAgents();
    for (const a of agents) {
      if (a.name?.startsWith(match) || a.id?.startsWith(match)) {
        try {
          await admin.http.delete(`/admin/agents/${encodeURIComponent(a.id)}`);
          result.deleted.push(a.id);
        } catch (err) {
          result.errors.push(`${a.id}: ${(err as Error).message}`);
        }
      }
    }
  } catch (err) {
    result.errors.push(`list failed: ${(err as Error).message}`);
  }
  return result;
}

/** Broad sweep entrypoint for `npm run cleanup`. Extended per-phase. */
export async function sweepAll(admin: AgmClient, runId?: string): Promise<SweepResult[]> {
  return [await sweepAgents(admin, runId)];
}
