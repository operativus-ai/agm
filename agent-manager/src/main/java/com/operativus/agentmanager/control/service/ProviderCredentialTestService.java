package com.operativus.agentmanager.control.service;

import com.operativus.agentmanager.control.dto.ProviderCredentialTestResponse;
import com.operativus.agentmanager.core.entity.ModelType;
import com.operativus.agentmanager.core.model.ModelRequest;
import com.operativus.agentmanager.core.registry.ModelOperations;
import com.operativus.agentmanager.core.registry.ProviderCredentialOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Domain Responsibility: Runs a live "test connection" probe for a provider credential —
 *     confirms a key authenticates and a chosen model answers a minimal prompt. Resolves the
 *     key to test as: the caller-supplied key when present, else the stored key for the
 *     caller's {@code (org, provider)}. Reuses {@link ModelOperations#testConnection} so the
 *     probe logic (transient provider client + tiny completion) lives in exactly one place.
 *
 *     Deliberately a separate bean from {@link ProviderCredentialService}: the credential
 *     service is a construction-time dependency of every {@code DynamicModelProvider} (via the
 *     {@code ProviderCredentialOperations} SPI), and {@code ModelOperations}'s implementation
 *     depends on those providers — injecting {@code ModelOperations} into the credential
 *     service itself would close a bean-creation cycle. This bean sits outside that cycle.
 * State: Stateless.
 */
@Service
public class ProviderCredentialTestService {

    private static final Logger log = LoggerFactory.getLogger(ProviderCredentialTestService.class);

    /** Providers that authenticate without an API key (local runtime). Blank key is allowed. */
    private static final String KEYLESS_PROVIDER = "OLLAMA";

    private final ModelOperations modelOps;
    private final ProviderCredentialOperations credentials;

    public ProviderCredentialTestService(ModelOperations modelOps, ProviderCredentialOperations credentials) {
        this.modelOps = modelOps;
        this.credentials = credentials;
    }

    /**
     * @summary Probe a provider credential against a specific model.
     * @logic Picks the key to test (supplied key wins; otherwise the stored (org, provider)
     *     key), then fires one minimal completion via {@link ModelOperations#testConnection}.
     *     Never throws — a failure (bad key, unknown model, network) is returned as
     *     {@code success=false} with the provider's message so the UI renders a diagnostic line.
     */
    public ProviderCredentialTestResponse test(String orgId, String provider, String apiKey, String model) {
        String effectiveKey = (apiKey != null && !apiKey.isBlank())
                ? apiKey
                : credentials.resolveDefaultKey(orgId, provider).orElse(null);

        boolean keyless = KEYLESS_PROVIDER.equalsIgnoreCase(provider);
        if (!keyless && (effectiveKey == null || effectiveKey.isBlank())) {
            return ProviderCredentialTestResponse.fail(provider, model, 0L,
                    "No API key to test — enter a key above, or save one for this provider first.");
        }

        long start = System.currentTimeMillis();
        try {
            ModelRequest probe = new ModelRequest(
                    "connection-test", provider, null, effectiveKey, model,
                    null, null, null, null, null, null,
                    ModelType.CHAT, null, null);
            modelOps.testConnection(probe);
            long elapsed = System.currentTimeMillis() - start;
            log.info("Provider credential test OK: provider={} model={} ({}ms)", provider, model, elapsed);
            return ProviderCredentialTestResponse.ok(provider, model, elapsed);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            String message = (e.getMessage() != null && !e.getMessage().isBlank())
                    ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("Provider credential test FAILED: provider={} model={} ({}ms): {}", provider, model, elapsed, message);
            return ProviderCredentialTestResponse.fail(provider, model, elapsed, message);
        }
    }
}
