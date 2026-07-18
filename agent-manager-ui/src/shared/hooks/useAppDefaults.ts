import { useQuery } from '@tanstack/react-query';
import { settingsApi } from '../../features/settings/api/settings-api';
import type { SettingsMap } from '../../features/settings/api/settings-api';

/**
 * Typed accessors over the global settings map.
 * Resolves convention-over-config defaults from the Settings API.
 */
export interface AppDefaults {
  /** Raw settings map from the backend */
  raw: SettingsMap;

  // Model defaults
  defaultModelRouter: string;
  defaultModelFast: string;
  defaultModelHeavy: string;
  defaultModelEmbedding: string;

  // Agent tuning defaults
  defaultTemperature: number;
  defaultTopP: number;
  defaultFinOpsRiskTier: string;
  defaultSecurityTier: number;
  defaultMaxConcurrent: number;

  // Optimization defaults
  compressionThresholdChars: number;
  summarizationThresholdTurns: number;
  crawlerMaxPages: number;
}

function parseDefaults(settings: SettingsMap): AppDefaults {
  const str = (key: string, fallback: string) => settings[key] || fallback;
  const num = (key: string, fallback: number) => {
    const val = settings[key];
    if (!val) return fallback;
    const parsed = parseFloat(val);
    return isNaN(parsed) ? fallback : parsed;
  };

  return {
    raw: settings,
    defaultModelRouter: str('DEFAULT_MODEL_ROUTER', ''),
    defaultModelFast: str('DEFAULT_MODEL_FAST', ''),
    defaultModelHeavy: str('DEFAULT_MODEL_HEAVY', ''),
    defaultModelEmbedding: str('DEFAULT_MODEL_EMBEDDING', ''),
    defaultTemperature: num('DEFAULT_TEMPERATURE', 0.7),
    defaultTopP: num('DEFAULT_TOP_P', 0.9),
    defaultFinOpsRiskTier: str('DEFAULT_FINOPS_RISK_TIER', 'LOW_RISK'),
    defaultSecurityTier: num('DEFAULT_SECURITY_TIER', 1),
    defaultMaxConcurrent: num('DEFAULT_MAX_CONCURRENT_EXECUTIONS', 5),
    compressionThresholdChars: num('COMPRESSION_THRESHOLD_CHARS', 8000),
    summarizationThresholdTurns: num('SUMMARIZATION_THRESHOLD_TURNS', 20),
    crawlerMaxPages: num('crawler.maxPages', 250),
  };
}

/**
 * React Query hook that fetches global settings and exposes typed defaults.
 * Uses a 5-minute stale time — after initial fetch, subsequent renders
 * are instant from cache. Follows the same pattern as useFinOps hooks.
 */
export function useAppDefaults() {
  const query = useQuery({
    queryKey: ['settings', 'defaults'],
    queryFn: () => settingsApi.getAllSettings(),
    staleTime: 300_000, // 5-minute cache
  });

  const defaults = query.data ? parseDefaults(query.data) : undefined;

  return {
    ...query,
    defaults,
  };
}
