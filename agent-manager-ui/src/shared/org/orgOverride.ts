/**
 * Super-admin org override. When set, ApiClient stamps `X-Org-Id` on every
 * request. Backend semantics make this safe by construction: Core's
 * TenantContextFilter resolves the JWT `org_id` claim FIRST and the header
 * second — a regular user's own org always wins, so the override only has
 * effect for claim-less principals (super admins).
 */
const KEY = 'agm.org-override';

export function getOrgOverride(): string | null {
    return localStorage.getItem(KEY);
}

export function setOrgOverride(orgId: string | null): void {
    if (orgId && orgId.trim()) {
        localStorage.setItem(KEY, orgId.trim());
    } else {
        localStorage.removeItem(KEY);
    }
}
