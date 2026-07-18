/**
 * Authority strings as issued by the backend (RoleType enum, `ROLE_`-prefixed)
 * and carried in the JWT / AuthContext user.roles. Using the `Role` union at
 * call sites turns a typo (`ROLE_ADMN`) into a compile error instead of a
 * silently-failing role gate.
 */
export const ROLES = {
  VIEWER: 'ROLE_VIEWER',
  USER: 'ROLE_USER',
  OPERATOR: 'ROLE_OPERATOR',
  ADMIN: 'ROLE_ADMIN',
  SUPER_ADMIN: 'ROLE_SUPER_ADMIN',
  /** Session/auth state, not an assignable identity role. */
  MFA_AUTHENTICATED: 'ROLE_MFA_AUTHENTICATED',
} as const;

export type Role = typeof ROLES[keyof typeof ROLES];

/** Assignable identity roles, least → most privileged (drives the user form). */
export const ASSIGNABLE_ROLES: Role[] = [
  ROLES.VIEWER,
  ROLES.USER,
  ROLES.OPERATOR,
  ROLES.ADMIN,
  ROLES.SUPER_ADMIN,
];
