// Feature-flag detection. Learns which opt-in lanes are live so scenarios
// tagged needsFlags auto-SKIP when the flag is off — instead of failing.
//
// Two flag families:
//  - skills / universalDispatch: their controllers are @ConditionalOnProperty
//    (havingValue=true), so when OFF the route 404s and when ON it responds
//    (200/401/403). A behavioral probe distinguishes them reliably.
//  - reranker / finopsGate: pure runtime behaviors with no dedicated endpoint;
//    not behaviorally probable up front → 'unknown' unless overridden by env
//    (AGM_FLAG_RERANKER / AGM_FLAG_FINOPS). Lanes needing them SKIP when unknown.

import { AgmApiError } from '../sdk/http.js';
import type { AgmClient } from '../sdk/agm.js';

/** true = on, false = off, undefined = unknown (cannot determine, no override). */
export interface FlagState {
  skills?: boolean;
  universalDispatch?: boolean;
  reranker?: boolean;
  finopsGate?: boolean;
}

/** Explicit overrides — skip behavioral probing where set. Supplied by the caller. */
export type FlagOverrides = FlagState;

/** A non-404 response means the @ConditionalOnProperty controller is registered → flag on. */
async function probePresent(client: AgmClient, path: string): Promise<boolean> {
  try {
    await client.http.get(path);
    return true; // 2xx — present
  } catch (err) {
    if (err instanceof AgmApiError) {
      // 404 → controller absent (flag off). 401/403 → present but gated (flag on).
      return err.status !== 404;
    }
    return false; // network error — treat as absent/unknown-off
  }
}

export async function detectFlags(client: AgmClient, overrides: FlagOverrides = {}): Promise<FlagState> {
  const o = overrides;
  const skills = o.skills ?? (await probePresent(client, '/v1/skills'));
  const universalDispatch = o.universalDispatch ?? (await probePresent(client, '/v1/routing-config'));
  return {
    skills,
    universalDispatch,
    reranker: o.reranker, // no probe — env-only
    finopsGate: o.finopsGate, // no probe — env-only
  };
}

export function describeFlags(f: FlagState): string {
  const s = (v: boolean | undefined) => (v === undefined ? '?' : v ? 'on' : 'off');
  return `skills=${s(f.skills)} universal-dispatch=${s(f.universalDispatch)} reranker=${s(f.reranker)} finops-gate=${s(f.finopsGate)}`;
}
