# Dynamic LLM Provider Configuration

Agent Manager supports **Google Gemini**, **OpenAI**, **Anthropic**, and **Ollama** at runtime. The provider for each agent is chosen from the `ModelEntity` row pointed at by the agent's `model_id`. API keys are loaded from the database — never from `.env`, Spring properties, or the OS environment.

## 1. Configure API keys

Keys are stored encrypted in the `provider_credentials` table, keyed by `(org_id, provider)`. Each provider gets one default key per org. Set them via the admin REST surface:

```bash
curl -X POST http://localhost:8080/api/v1/provider-credentials \
  -H "Authorization: Bearer <admin-jwt>" \
  -H "Content-Type: application/json" \
  -d '{
        "provider": "OPENAI",
        "apiKey": "sk-proj-...",
        "label": "Production OpenAI key"
      }'
```

Provider values: `OPENAI`, `ANTHROPIC`, `GOOGLE`, `OLLAMA`.

Responses mask the stored key — only a four-character tail preview is returned. Keys are encrypted at rest via `OutboundApiKeyConverter` (AES-256-GCM).

### Per-model override

A row in `models` can carry its own `api_key` value (encrypted via the same converter). When present it takes precedence over the per-org default. Use this for:

- Mixing two paid OpenAI accounts in the same org (one model per account)
- Pinning a specific test/staging key to a single model without touching the org default

If both sources are absent, `AbstractDynamicModelProvider.resolveApiKey` throws with a message pointing the admin at `POST /api/v1/provider-credentials`. Ollama is the exception — self-hosted endpoints typically require no key, so `OllamaModelProvider.buildChatModel` does not call `resolveApiKey` at all.

## 2. Configure agents

Each agent references a `ModelEntity` by `model_id`. The model row carries `provider` (one of `OPENAI` / `ANTHROPIC` / `GOOGLE` / `OLLAMA`) and `model_name` (the remote API identifier, e.g. `gpt-4o`, `claude-haiku-4-5-20251001`, `gemini-2.5-pro`). The provider field tells AGM which strategy to dispatch through; `model_name` is the exact string sent over the wire.

```sql
UPDATE agents SET model_id = 'gemini-2.5-pro' WHERE id = 'assistant';
```

## 3. Fallback chain

Agents can carry an ordered `fallback_model_ids` JSONB list (changeset 084). When the primary model is rate-limited or quota-exceeded, the run retries through each fallback in order. Non-quota errors (auth, model-not-found, malformed-request) break the chain immediately — see `AgentService.tryFallbackChain` / `AgentStreamManager.tryStreamFallbackChain`.

## 4. Multi-provider simultaneously

All four provider strategies are always wired at startup (`DynamicProviderInitializer` injects literal dummy keys to pass Spring AI's eager validation). Real key resolution happens per-request based on the agent's selected `ModelEntity`, so different agents in the same JVM can target different providers concurrently with no extra config.
