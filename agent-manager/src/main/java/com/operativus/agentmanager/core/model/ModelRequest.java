package com.operativus.agentmanager.core.model;

import com.operativus.agentmanager.core.entity.DefaultModelSlot;
import com.operativus.agentmanager.core.entity.ModelType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

/**
 * Domain Responsibility: Acts as an immutable data transfer object for creating or updating an LLM or Embedding model configuration.
 * State: Stateless (Immutable Record carrier)
 *
 * <p>Size limits on string fields mirror the underlying VARCHAR(255) columns in the
 * {@code models} table — applying them at the request boundary surfaces a 400 Bad Request
 * with a structured field error instead of letting the truncated/oversized value reach
 * Postgres and produce a 500. {@code apiKey} is bounded at 1024 to accommodate longer
 * provider tokens (AWS Bedrock IDs, custom-provider opaque blobs) while still preventing
 * a request-body DoS.
 */
public record ModelRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(max = 255) String provider,
        @URL @Size(max = 255) String baseUrl,
        @Size(max = 1024) String apiKey,
        @NotBlank @Size(max = 255) String modelName,
        @Positive Integer maxContextTokens,
        @Positive Integer maxOutputTokens,
        @Positive Integer thinkingBudgetTokens,
        Boolean supportsTools,
        Boolean supportsVision,
        Boolean supportsSystemInstructions,
        ModelType modelType,
        DefaultModelSlot defaultSlot,
        /** §6 M-12: optional per-model rate limit, in requests per minute. Null = no per-model
         *  override. Bounded at 60_000 (1000 RPS) — anything above that signals a misconfiguration. */
        @Positive @Max(60_000) Integer rateLimitRpm
) {}
