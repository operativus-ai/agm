package com.operativus.agentmanager.core.model;

import com.operativus.agentmanager.core.entity.ModelType;
import java.time.LocalDateTime;

/**
 * Domain Responsibility: Acts as an immutable data transfer object representing a registered LLM or Embedding model configuration.
 * State: Stateless (Immutable Record carrier)
 */
public record ModelDTO(
        String id,
        String name,
        String provider,
        String baseUrl,
        String modelName,
        Boolean supportsTools,
        Boolean supportsVision,
        Boolean supportsSystemInstructions,
        Integer maxContextTokens,
        Integer maxOutputTokens,
        Integer thinkingBudgetTokens,
        ModelType modelType,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        long agentCount,
        /** §7 Model Pinger: most recent liveness probe outcome. {@code null} = never pinged.
         *  Surfaced in the UI as a badge but NOT consulted by the agent-runs path — a stale
         *  {@code false} must never block a request that the provider has since recovered for. */
        Boolean available,
        /** §7 Model Pinger: wall-clock instant of the last ping that produced {@code available}. */
        LocalDateTime lastPingedAt,
        /** §6 M-10 Usage stats: number of agent_runs in the last
         *  {@code ModelService.USAGE_WINDOW_DAYS} that target an agent currently configured
         *  against this model. Defaults to 0 for newly created rows. */
        long runCount,
        /** §6 M-12: optional per-model RPM override. {@code null} = no per-model gate (only the
         *  global per-user RateLimitingFilter applies). Phase 2 will install enforcement at
         *  the LLM call site keyed by model id. */
        Integer rateLimitRpm,
        /** True when the {@code models.api_key} column carries a non-blank value. The plaintext
         *  key is never returned over the wire — this boolean is the only signal the UI gets
         *  that a per-model override is configured, distinct from falling through to the
         *  per-(org, provider) ProviderCredential default. */
        boolean apiKeyConfigured
) {}
