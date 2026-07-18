// Identity resolution (NFR-5). Resolves admin / userA / userB from an explicit
// creds pool by logging each in; reports availability so scenarios can gate on
// it. Two orgs (userA in one, userB in another) unlock the isolation suite in
// Phase 5. Config-free: the caller (CLI reads env; UI reads a form) supplies the
// baseUrl + creds, so this module runs unchanged in Node and the browser.

import { AgmClient } from '../sdk/agm.js';
import type { AuthResponse } from '../types.js';

export interface IdentityCreds {
  label: string;
  username: string;
  password: string;
}

export interface CredsPool {
  admin?: IdentityCreds;
  userA?: IdentityCreds;
  userB?: IdentityCreds;
}

export interface ResolvedIdentity {
  label: string;
  /** Always present (unauthenticated if login failed / not configured). */
  client: AgmClient;
  available: boolean;
  reason?: string;
  auth?: AuthResponse;
}

export interface IdentityPool {
  admin: ResolvedIdentity;
  userA: ResolvedIdentity;
  userB: ResolvedIdentity;
  /** Unauthenticated client — for 401/taxonomy probes. */
  anon: AgmClient;
}

async function resolve(baseUrl: string, label: string, creds: IdentityCreds | undefined): Promise<ResolvedIdentity> {
  const client = new AgmClient(baseUrl);
  if (!creds) {
    return { label, client, available: false, reason: 'not configured' };
  }
  try {
    const auth = await client.login(creds.username, creds.password);
    return { label, client, available: true, auth };
  } catch (err) {
    return { label, client, available: false, reason: (err as Error).message };
  }
}

export async function resolveIdentities(baseUrl: string, pool: CredsPool): Promise<IdentityPool> {
  const [admin, userA, userB] = await Promise.all([
    resolve(baseUrl, 'admin', pool.admin),
    resolve(baseUrl, 'userA', pool.userA),
    resolve(baseUrl, 'userB', pool.userB),
  ]);
  return { admin, userA, userB, anon: new AgmClient(baseUrl) };
}

/** Build a pre-authenticated pool from already-obtained clients (UI login path). */
export function poolFromClients(clients: {
  admin?: { client: AgmClient; auth?: AuthResponse };
  userA?: { client: AgmClient; auth?: AuthResponse };
  userB?: { client: AgmClient; auth?: AuthResponse };
  anon: AgmClient;
}): IdentityPool {
  const wrap = (label: string, c?: { client: AgmClient; auth?: AuthResponse }): ResolvedIdentity =>
    c
      ? { label, client: c.client, available: true, auth: c.auth }
      : { label, client: clients.anon, available: false, reason: 'not signed in' };
  return {
    admin: wrap('admin', clients.admin),
    userA: wrap('userA', clients.userA),
    userB: wrap('userB', clients.userB),
    anon: clients.anon,
  };
}

/**
 * Best-effort admin-provisioned user (Phase 5). Body mirrors UserAdminDTO.CreateRequest
 * as a superset — VERIFY the exact field names against the DTO before relying on it.
 * Not invoked in Phase 0.
 */
export async function provisionUser(
  admin: AgmClient,
  input: { username: string; email: string; password: string; roles?: string[]; orgId?: string },
): Promise<unknown> {
  return admin.http.post('/admin/users', input);
}
