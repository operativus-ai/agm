package com.operativus.agentmanager.core.registry;

import java.util.Optional;

/**
 * Domain Responsibility: SPI seam between {@code compute} and {@code control} for resolving
 * per-(org, provider) LLM API keys. Consumed by {@code AbstractDynamicModelProvider.resolveApiKey}
 * when a {@code ModelEntity} does not carry a per-model override.
 * State: Stateless.
 */
public interface ProviderCredentialOperations {

    /**
     * @summary Returns the configured API key for the given org + provider, decrypted.
     * @logic Looks up {@code provider_credentials} on the {@code (org_id, provider)} unique
     *   constraint. Returns {@code Optional.empty()} if no row exists. Caller decides whether
     *   absence should throw or be tolerated (Ollama allows blank; remote providers do not).
     */
    Optional<String> resolveDefaultKey(String orgId, String provider);
}
