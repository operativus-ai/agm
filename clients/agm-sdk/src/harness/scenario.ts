// Scenario model + execution context. Every requirement (TC-*) becomes one
// Scenario; the runner discovers, gates, executes, and reports them uniformly.

import type { AgmClient } from '../sdk/agm.js';
import type { IdentityPool } from '../fixtures/identities.js';
import type { Budget } from './budget.js';
import type { FlagState } from './flags.js';

export type Tier = 'T0' | 'T1' | 'T2';
export type Priority = 'P1' | 'P2' | 'P3';
export type Outcome = 'PASS' | 'FAIL' | 'WARN' | 'SKIP';

/** What a scenario needs to run. Unmet prereqs → SKIP (never FAIL). */
export interface Prereqs {
  /** T1+ needs a reachable backend. */
  backend?: boolean;
  admin?: boolean;
  userA?: boolean;
  userB?: boolean;
  /** A resolved default agent (config pin or discovered). */
  agent?: boolean;
  /**
   * T2: needs an LLM provider key. NOT gated up front (presence is unknowable
   * without trying) — the runner converts a provider-key error into WARN.
   */
  providerKey?: boolean;
  /** Named feature flags that must be ON (see FlagState keys). */
  flags?: Array<keyof FlagState>;
}

export interface ScenarioResult {
  outcome: Outcome;
  note?: string;
  /** Freeform structured evidence for the JSON report. */
  evidence?: Record<string, unknown>;
}

export interface Scenario {
  id: string; // e.g. 'TC-AUTH-1'
  domain: string; // 'F1'..'F12'
  title: string;
  tier: Tier;
  priority: Priority;
  prereqs?: Prereqs;
  run(ctx: Ctx): Promise<ScenarioResult>;
  /** Optional explicit teardown (in addition to ctx.track'd teardowns). */
  cleanup?(ctx: Ctx): Promise<void>;
}

/** Execution context handed to every scenario. */
export class Ctx {
  /** Resolved default agent id, seeded from the pin and set by the discovery scenario. */
  agentId?: string;
  private readonly teardowns: Array<() => Promise<void>> = [];

  constructor(
    public readonly runId: string,
    public readonly prefix: string,
    /** Base URL of the AGM backend — for scenarios that construct ad-hoc clients. */
    public readonly baseUrl: string,
    public readonly identities: IdentityPool,
    public readonly flags: FlagState,
    public readonly budget: Budget,
    /** Optional configured agent pin (else discovery picks the first active agent). */
    public readonly agentIdPin?: string,
  ) {
    this.agentId = agentIdPin;
  }

  get anon(): AgmClient {
    return this.identities.anon;
  }

  admin(): AgmClient {
    return this.require('admin');
  }
  userA(): AgmClient {
    return this.require('userA');
  }
  userB(): AgmClient {
    return this.require('userB');
  }

  private require(label: 'admin' | 'userA' | 'userB'): AgmClient {
    const id = this.identities[label];
    if (!id.available) throw new Error(`identity '${label}' unavailable: ${id.reason ?? 'unknown'}`);
    return id.client;
  }

  log(message: string): void {
    console.log(`     ${message}`);
  }

  /** Register a fixture teardown; run in reverse order after the scenario. */
  track(teardown: () => Promise<void>): void {
    this.teardowns.push(teardown);
  }

  async runTeardowns(): Promise<void> {
    for (let i = this.teardowns.length - 1; i >= 0; i--) {
      await this.teardowns[i]().catch(() => {});
    }
    this.teardowns.length = 0;
  }
}

// ── Result helpers ────────────────────────────────────────────────────────────

export const pass = (note?: string, evidence?: Record<string, unknown>): ScenarioResult => ({
  outcome: 'PASS',
  note,
  evidence,
});
export const fail = (note?: string, evidence?: Record<string, unknown>): ScenarioResult => ({
  outcome: 'FAIL',
  note,
  evidence,
});
export const warn = (note?: string, evidence?: Record<string, unknown>): ScenarioResult => ({
  outcome: 'WARN',
  note,
  evidence,
});
export const skip = (note?: string): ScenarioResult => ({ outcome: 'SKIP', note });
