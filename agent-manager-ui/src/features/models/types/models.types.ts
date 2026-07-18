export type DefaultModelSlot = 'ROUTER' | 'FAST' | 'HEAVY' | 'EMBEDDING';

export interface ModelConfig {
  id: string;
  name: string;
  provider: 'OPENAI' | 'ANTHROPIC' | 'OLLAMA' | 'GOOGLE' | string;
  baseUrl?: string;
  modelName?: string;
  supportsTools?: boolean;
  supportsVision?: boolean;
  supportsSystemInstructions?: boolean;
  maxContextTokens?: number;
  maxOutputTokens?: number;
  thinkingBudgetTokens?: number;
  modelType?: 'CHAT' | 'EMBEDDING';
  createdAt?: string;
  updatedAt?: string;
  agentCount: number;
  /** §7 Model Pinger: most recent liveness probe outcome.
   *  `undefined` = never pinged (e.g. fresh row, or backend running pre-Liquibase 040).
   *  Surfaced as a badge in model pickers and on ModelsPage; not consulted to gate runs. */
  available?: boolean;
  /** §7 Model Pinger: ISO instant of the last probe that produced `available`. */
  lastPingedAt?: string;
  /** §6 M-10 Usage stats: number of agent_runs in the last 30 days that target an agent
   *  currently configured against this model. 0 for newly created rows. */
  runCount: number;
  /** §6 M-12: optional per-model rate limit, in requests per minute.
   *  `null`/`undefined` = no per-model gate (only the global per-user RateLimitingFilter applies).
   *  When set, AGM throws 429 with type=urn:problem-type:model-rate-limit-exceeded once
   *  the ceiling is hit; the gate sits before ChatClient construction so a rejected
   *  request never burns advisor-chain or upstream provider tokens. */
  rateLimitRpm?: number | null;
  /** True when this model row carries a non-blank per-model `api_key` override (encrypted
   *  at rest). Plaintext keys are never returned over the wire; this boolean lets the UI
   *  show "key is configured" without exposing key bytes. When false, the model resolves
   *  against the per-(org, provider) ProviderCredential at call time. */
  apiKeyConfigured?: boolean;
}

/** Wire-format result of a manual liveness probe via `POST /api/models/{id}/test`.
 *  Backend `ModelPingResult` record. `available=false` carries an `errorMessage`
 *  describing the provider failure (timeout, missing key, etc.) so the UI can
 *  render diagnostics without parsing exception text. */
export interface ModelPingResult {
  modelId: string;
  available: boolean;
  latencyMs: number;
  errorMessage: string | null;
}

export interface ModelRequest {
  name: string;
  provider: string;
  baseUrl?: string;
  apiKey?: string;
  modelName?: string;
  supportsTools?: boolean;
  supportsVision?: boolean;
  supportsSystemInstructions?: boolean;
  maxContextTokens?: number;
  maxOutputTokens?: number;
  thinkingBudgetTokens?: number;
  modelType?: 'CHAT' | 'EMBEDDING';
  defaultSlot?: DefaultModelSlot;
  /** §6 M-12: optional per-model rate limit, in requests per minute.
   *  Backend bounds: @Positive @Max(60_000). `undefined`/absent = no override. */
  rateLimitRpm?: number;
}
