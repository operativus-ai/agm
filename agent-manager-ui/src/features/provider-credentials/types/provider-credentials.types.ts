/**
 * Mirrors BE control.dto.ProviderCredentialResponse. `apiKeyPreview` is the only
 * key-derived field the BE returns — full keys never cross the wire on read.
 */
export interface ProviderCredentialResponse {
  id: string;
  orgId: string;
  provider: string;
  label: string | null;
  apiKeyPreview: string;
  createdAt: string | null;
  updatedAt: string | null;
  createdBy: string | null;
  updatedBy: string | null;
  version: number;
}

/**
 * Mirrors BE control.dto.ProviderCredentialRequest. `apiKey` is optional on EDIT — a
 * blank value tells the BE to keep the stored key (the server never returns it, so editing
 * a label must not force a re-type). On CREATE the BE requires a non-blank key.
 */
export interface ProviderCredentialRequest {
  provider: string;
  apiKey: string;
  label?: string | null;
}

/** Mirrors BE control.dto.ProviderCredentialTestRequest. `apiKey` blank => test the stored key. */
export interface ProviderCredentialTestRequest {
  provider: string;
  apiKey?: string;
  model: string;
}

/**
 * Mirrors BE control.dto.ProviderCredentialTestResponse. Always HTTP 200 — pass/fail is in
 * `success`; `message` carries the provider's diagnostic on failure.
 */
export interface ProviderCredentialTestResponse {
  success: boolean;
  provider: string;
  model: string;
  latencyMs: number;
  message: string | null;
}

export const PROVIDERS = ['OPENAI', 'ANTHROPIC', 'GOOGLE', 'OLLAMA'] as const;
export type ProviderName = typeof PROVIDERS[number];
