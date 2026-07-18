import React from 'react';
import { LuLock } from 'react-icons/lu';
import { useLicense } from './useLicense';

/**
 * Renders children only when the active license carries the feature; otherwise shows an
 * upgrade panel (or a custom fallback). UX gate only — enterprise endpoints don't exist
 * on a Core backend regardless.
 */
export const RequiresFeature: React.FC<{
  feature: string;
  fallback?: React.ReactNode;
  children: React.ReactNode;
}> = ({ feature, fallback, children }) => {
  const { license, isLoading } = useLicense();
  if (isLoading) return null;
  if (license.features.includes(feature)) return <>{children}</>;
  if (fallback !== undefined) return <>{fallback}</>;
  return (
    <div className="flex flex-col items-center justify-center gap-3 py-16 px-6 text-center border border-obsidian-stroke rounded-lg bg-obsidian-surface">
      <LuLock className="w-8 h-8 text-(--theme-muted)" />
      <div className="font-semibold">Enterprise feature</div>
      <p className="text-sm text-(--theme-muted) max-w-md">
        This capability is part of AGM Enterprise. Contact your administrator or
        support@operativus.ai to enable it.
      </p>
    </div>
  );
};
