// Env-driven config. See .env.example — export the vars (tsx/vitest don't
// auto-load .env files).
//
// Phase 0 grows this from single-user to multi-identity (admin / userA / userB),
// adds a token/cost budget ceiling (NFR-7) and feature-flag overrides. The
// original single-user fields (username/password/live) are preserved so the
// standalone smoke.ts keeps working.

import type { IdentityCreds } from '@agm/sdk';

function creds(label: string, userVar: string, passVar: string): IdentityCreds | undefined {
  const username = process.env[userVar];
  const password = process.env[passVar];
  return username && password ? { label, username, password } : undefined;
}

/** admin falls back to the legacy AGM_USERNAME/PASSWORD so existing setups keep working. */
const admin =
  creds('admin', 'AGM_ADMIN_USERNAME', 'AGM_ADMIN_PASSWORD') ??
  creds('admin', 'AGM_USERNAME', 'AGM_PASSWORD');

/** Feature-flag override: '1' → on, '0' → off, unset → behavioral probe / unknown. */
function flagOverride(envVar: string): boolean | undefined {
  const v = process.env[envVar];
  if (v === '1') return true;
  if (v === '0') return false;
  return undefined;
}

export const config = {
  baseUrl: (process.env.AGM_BASE_URL ?? 'http://localhost:8080').replace(/\/$/, ''),

  // Legacy single-user fields (= admin identity) kept for smoke.ts.
  username: admin?.username ?? '',
  password: admin?.password ?? '',

  /** Optional agent pin; otherwise the discovery scenario picks the first active agent. */
  agentId: process.env.AGM_AGENT_ID || undefined,

  /** Gate for the live vitest suite. */
  live: process.env.AGM_LIVE === '1',

  /** Identities. admin unlocks admin lanes; userA/userB unlock the isolation suite (Phase 5). */
  identities: {
    admin,
    userA: creds('userA', 'AGM_USER_A_USERNAME', 'AGM_USER_A_PASSWORD'),
    userB: creds('userB', 'AGM_USER_B_USERNAME', 'AGM_USER_B_PASSWORD'),
  },

  /** Token/cost ceiling for T2 (LLM) scenarios; 0 = no ceiling. NFR-7. */
  budgetUsd: Number(process.env.AGM_BUDGET_USD ?? '0'),

  /** Feature-flag overrides — skip behavioral probing when set. */
  flagOverrides: {
    skills: flagOverride('AGM_FLAG_SKILLS'),
    universalDispatch: flagOverride('AGM_FLAG_UNIVERSAL_DISPATCH'),
    reranker: flagOverride('AGM_FLAG_RERANKER'),
    finopsGate: flagOverride('AGM_FLAG_FINOPS'),
  },
};

/** Back-compat for smoke.ts — returns the admin identity's creds or throws. */
export function requireCreds(): { username: string; password: string } {
  if (!config.username || !config.password) {
    throw new Error(
      'AGM_ADMIN_USERNAME / AGM_ADMIN_PASSWORD (or legacy AGM_USERNAME / AGM_PASSWORD) not set. ' +
        'Use a dev user whose org_id is set (self-registered users have null org_id and fail ' +
        'agent-scope resolution). See .env.example.',
    );
  }
  return { username: config.username, password: config.password };
}
