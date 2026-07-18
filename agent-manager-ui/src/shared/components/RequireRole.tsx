import React from 'react';
import { Navigate } from 'react-router-dom';
import { useAuth } from '../../features/auth/context/AuthContext';

import type { Role } from '../constants/roles';

interface RequireRoleProps {
  role: Role;
  children: React.ReactNode;
  redirectTo?: string;
}

export const RequireRole: React.FC<RequireRoleProps> = ({
  role,
  children,
  redirectTo = '/dashboard',
}) => {
  const { user, isLoading } = useAuth();

  if (isLoading) return null;

  if (!user?.roles?.includes(role)) {
    return <Navigate to={redirectTo} replace />;
  }

  return <>{children}</>;
};
