import React from 'react';
import { SiAnthropic, SiGooglegemini, SiOllama, SiOpenai } from 'react-icons/si';
import { LuServer } from 'react-icons/lu';
import type { IconType } from 'react-icons';

/**
 * Maps a model `provider` string (case-insensitive) to a brand icon. The map is
 * intentionally narrow — provider strings come from the backend's
 * `DynamicProviderInitializer` registry, which is finite and stable. Unknown
 * providers fall back to a neutral server glyph rather than a missing icon.
 *
 * Brand colours match each vendor's own palette (Simple Icons CC0 reference);
 * keeps the visual cue legible at the small sizes used in the picker dropdown
 * and the Models table.
 */
const PROVIDER_REGISTRY: Record<string, { icon: IconType; color: string }> = {
  OPENAI: { icon: SiOpenai, color: '#10A37F' },
  ANTHROPIC: { icon: SiAnthropic, color: '#D97757' },
  GOOGLE: { icon: SiGooglegemini, color: '#4285F4' },
  OLLAMA: { icon: SiOllama, color: '#FFFFFF' },
};

interface ProviderIconProps {
  provider: string | undefined;
  size?: number;
  /** Override the brand colour with currentColor — useful when embedding in a
   *  themed container that already sets text-* classes. */
  monochrome?: boolean;
  className?: string;
}

export const ProviderIcon: React.FC<ProviderIconProps> = ({ provider, size = 14, monochrome, className }) => {
  const key = (provider ?? '').toUpperCase();
  const entry = PROVIDER_REGISTRY[key];
  if (!entry) {
    return <LuServer size={size} className={className} aria-label={provider ?? 'unknown provider'} />;
  }
  const Icon = entry.icon;
  return (
    <Icon
      size={size}
      className={className}
      color={monochrome ? undefined : entry.color}
      aria-label={key}
    />
  );
};
