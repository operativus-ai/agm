import React from 'react';
import { Navigate } from 'react-router-dom';
import { useAuth } from '../../features/auth/context/AuthContext';

import type { Role } from '../constants/roles';

interface RequireAnyRoleProps {
  roles: Role[];
  children: React.ReactNode;
  redirectTo?: string;
}

/**
 * Route guard that admits a user holding ANY of the given roles. Use for tiers where several
 * roles qualify (e.g. ROLE_ADMIN or ROLE_SUPER_ADMIN) — RequireRole only matches one exact
 * role and has no hierarchy, so it would wrongly block a higher tier.
 */
export const RequireAnyRole: React.FC<RequireAnyRoleProps> = ({
  roles,
  children,
  redirectTo = '/dashboard',
}) => {
  const { user, isLoading } = useAuth();

  if (isLoading) return null;

  if (!user?.roles?.some((r) => (roles as readonly string[]).includes(r))) {
    return <Navigate to={redirectTo} replace />;
  }

  return <>{children}</>;
};
