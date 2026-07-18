export interface ComposioActionConfigResponse {
  id: string;
  actionName: string;
  llmToolName: string;
  tier: 1 | 2 | 3;
  enabled: boolean;
  version: number;
  createdAt: string;
  updatedAt: string;
  createdBy: string;
  updatedBy: string;
}

export interface ComposioActionConfigCreateRequest {
  actionName: string;
  tier: 1 | 2 | 3;
  enabled: boolean;
}

export interface ComposioActionConfigUpdateRequest {
  tier: 1 | 2 | 3;
  enabled: boolean;
  version: number;
}

export interface ComposioConnectionConfigResponse {
  id: string;
  orgId: string;
  connectionId: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface ComposioConnectionConfigUpsertRequest {
  connectionId: string;
  version: number | null;
}

export interface ConfigDriftActionDrift {
  totalInDb: number;
  enabledInDb: number;
  inLiveRegistry: number;
  inRegistryNotInDb: string[];
  inDbDisabled: string[];
  inSync: string[];
}

export interface ConfigDriftConnectionRow {
  orgId: string;
  connectionId: string;
  updatedAt: string;
}

export interface ConfigDriftResponse {
  generatedAt: string;
  registrySource: 'DB' | 'PROPERTIES_FALLBACK';
  registryWasTruncated: boolean;
  actionDrift: ConfigDriftActionDrift;
  connections: ConfigDriftConnectionRow[];
  orgsWithoutConnection: string[];
}

// ── Upstream catalog (browse + bulk import) ──────────────────
export interface ComposioCatalogAction {
  name: string;
  app: string | null;
  displayName: string | null;
  description: string | null;
  deprecated: boolean | null;
}

export interface ComposioCatalogListResponse {
  items: ComposioCatalogAction[];
  totalReturned: number;
  appFilter: string | null;
}

export interface ComposioCatalogImportRequest {
  app: string;
  overwriteExisting: boolean | null;
  defaultTier: 1 | 2 | 3 | null;
}

export interface ComposioCatalogImportFailure {
  actionName: string;
  reason: string;
}

export interface ComposioCatalogImportResponse {
  app: string;
  totalFetched: number;
  created: string[];
  skippedExisting: string[];
  failures: ComposioCatalogImportFailure[];
}

export const TIER_LABELS: Record<number, string> = {
  1: 'Auto-execute',
  2: 'HITL-gated',
  3: 'Destructive/Approval',
};

export const TIER_DESCRIPTIONS: Record<number, string> = {
  1: 'Executes automatically without human review',
  2: 'Requires human-in-the-loop approval before execution',
  3: 'Destructive — requires explicit admin approval',
};
