import { useQuery } from '@tanstack/react-query';
import { ApiClient, ApiError } from '../api/client';

export interface LicenseInfo {
  edition: 'core' | 'enterprise';
  features: string[];
  expiresAt?: string;
  /** Org-count ceiling enforced by the EE org registry; absent = unlimited. */
  maxTenants?: number;
  issuedTo?: string;
}

export const CORE_LICENSE: LicenseInfo = { edition: 'core', features: [] };

/**
 * Edition/license state for UI gating (agm-core-oss-execution.md §5.2). A Core backend has
 * no license endpoint (it lives in the enterprise jar) — the 404 resolves to the core
 * edition. Client gating is UX only; the real gate is the backend, where enterprise
 * endpoints don't exist in a Core deployment.
 */
export function useLicense(): { license: LicenseInfo; isLoading: boolean } {
  const { data, isLoading } = useQuery<LicenseInfo>({
    queryKey: ['license'],
    queryFn: async () => {
      try {
        return await ApiClient.get<LicenseInfo>('/v1/license');
      } catch (err) {
        if (err instanceof ApiError && err.status === 404) return CORE_LICENSE;
        throw err;
      }
    },
    staleTime: 5 * 60 * 1000,
    retry: false,
  });
  return { license: data ?? CORE_LICENSE, isLoading };
}

export function useHasFeature(feature: string): boolean {
  const { license } = useLicense();
  return license.features.includes(feature);
}
