// Scenario runner: capability detection → tier/prereq gating → execution with
// timing → provider-key-to-WARN conversion → teardown. Pure orchestration; the
// scenarios own their assertions.

import { AgmApiError } from '../sdk/http.js';
import { BudgetExceededError } from './budget.js';
import {
  Ctx,
  type Outcome,
  type Scenario,
  type ScenarioResult,
  type Tier,
} from './scenario.js';
import type { IdentityPool } from '../fixtures/identities.js';
import type { FlagState } from './flags.js';

export interface Filters {
  /** Upper tier bound: T0 → only T0; T1 → T0+T1; T2 → all. Default all. */
  maxTier?: Tier;
  /** Restrict to a domain (e.g. 'F5'). */
  domain?: string;
  /** Restrict to exact scenario ids. */
  ids?: string[];
}

export interface EnvCaps {
  reachable: boolean;
  admin: boolean;
  userA: boolean;
  userB: boolean;
}

export interface ScenarioReport {
  id: string;
  domain: string;
  title: string;
  tier: Tier;
  priority: string;
  outcome: Outcome;
  note?: string;
  durationMs: number;
  evidence?: Record<string, unknown>;
}

const TIER_RANK: Record<Tier, number> = { T0: 0, T1: 1, T2: 2 };

export function envCaps(identities: IdentityPool, reachable: boolean): EnvCaps {
  return {
    reachable,
    admin: identities.admin.available,
    userA: identities.userA.available,
    userB: identities.userB.available,
  };
}

function selects(s: Scenario, f: Filters): boolean {
  if (f.ids && f.ids.length && !f.ids.includes(s.id)) return false;
  if (f.domain && s.domain !== f.domain) return false;
  if (f.maxTier && TIER_RANK[s.tier] > TIER_RANK[f.maxTier]) return false;
  return true;
}

/** Returns a SKIP reason if prereqs (that are gated up front) are unmet, else null. */
function skipReason(s: Scenario, caps: EnvCaps, flags: FlagState, agentId?: string): string | null {
  const p = s.prereqs;
  if (!p) return null;
  if ((p.backend || s.tier !== 'T0') && !caps.reachable) return 'backend not reachable';
  if (p.admin && !caps.admin) return 'no admin identity';
  if (p.userA && !caps.userA) return 'no userA identity';
  if (p.userB && !caps.userB) return 'no userB identity (2nd org)';
  if (p.agent && !agentId) return 'no agent resolved';
  for (const flag of p.flags ?? []) {
    if (flags[flag] !== true) return `flag '${String(flag)}' ${flags[flag] === undefined ? 'unknown' : 'off'}`;
  }
  return null;
}

export interface RunOptions extends Filters {
  reachable: boolean;
  dryRun?: boolean;
}

export async function runScenarios(
  scenarios: Scenario[],
  ctx: Ctx,
  opts: RunOptions,
): Promise<ScenarioReport[]> {
  const caps = envCaps(ctx.identities, opts.reachable);
  const selected = scenarios.filter((s) => selects(s, opts));
  const reports: ScenarioReport[] = [];

  for (const s of selected) {
    const base = {
      id: s.id,
      domain: s.domain,
      title: s.title,
      tier: s.tier,
      priority: s.priority,
    };

    // Dynamic prereqs (agentId may have been set by an earlier scenario) are
    // evaluated here, right before execution.
    const reason = skipReason(s, caps, ctx.flags, ctx.agentId);
    if (reason || opts.dryRun) {
      reports.push({ ...base, outcome: 'SKIP', note: opts.dryRun ? 'dry-run' : reason!, durationMs: 0 });
      logLine(s, opts.dryRun ? 'SKIP' : 'SKIP', opts.dryRun ? '(dry-run)' : reason!);
      continue;
    }

    // Budget guard for LLM scenarios.
    if (s.tier === 'T2') {
      try {
        ctx.budget.guard();
      } catch (err) {
        reports.push({ ...base, outcome: 'SKIP', note: (err as BudgetExceededError).message, durationMs: 0 });
        logLine(s, 'SKIP', 'budget ceiling reached');
        continue;
      }
    }

    const started = Date.now();
    let result: ScenarioResult;
    try {
      result = await s.run(ctx);
    } catch (err) {
      // Provider-key gap is an environment condition, not a contract failure.
      if (s.prereqs?.providerKey && err instanceof AgmApiError && err.kind === 'provider-key') {
        result = { outcome: 'WARN', note: `${err.message} — add a credential via POST /api/v1/provider-credentials` };
      } else {
        result = { outcome: 'FAIL', note: (err as Error).message };
      }
    } finally {
      try {
        if (s.cleanup) await s.cleanup(ctx);
      } catch {
        /* ignore cleanup errors */
      }
      await ctx.runTeardowns();
    }

    const durationMs = Date.now() - started;
    reports.push({ ...base, outcome: result.outcome, note: result.note, durationMs, evidence: result.evidence });
    logLine(s, result.outcome, result.note, durationMs);
  }

  return reports;
}

function logLine(s: Scenario, outcome: Outcome, note?: string, ms?: number): void {
  const icon = { PASS: '✅', FAIL: '❌', WARN: '⚠️ ', SKIP: '⏭️ ' }[outcome];
  const time = ms !== undefined && ms > 0 ? ` (${ms}ms)` : '';
  console.log(`${icon} [${s.tier}] ${s.id} — ${s.title}${time}${note ? `\n     ${note}` : ''}`);
}
