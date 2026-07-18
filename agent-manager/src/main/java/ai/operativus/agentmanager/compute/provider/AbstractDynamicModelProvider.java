package ai.operativus.agentmanager.compute.provider;

import ai.operativus.agentmanager.core.callback.AgentContextHolder;
import ai.operativus.agentmanager.core.entity.ModelEntity;
import ai.operativus.agentmanager.core.registry.ProviderCredentialOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Domain Responsibility: Shared provider-level helpers including per-(org, provider)
 * API key resolution. Keys come only from the database — never from {@code .env}, Spring
 * properties, or the OS environment. Two database sources, checked in order:
 * <ol>
 *   <li>{@code ModelEntity.apiKey} — per-model override (encrypted at rest)</li>
 *   <li>{@code provider_credentials} row for {@code (caller orgId, model.provider)} — the
 *       per-org default configured via {@code /api/v1/provider-credentials}</li>
 * </ol>
 *
 * If neither source yields a non-blank key, throws
 * {@link ai.operativus.agentmanager.core.exception.MissingProviderKeyException} with an
 * actionable message. Concrete providers that legitimately need no key (Ollama) skip the call
 * entirely.
 *
 * State: Stateless (Configuration Strategy).
 */
public abstract class AbstractDynamicModelProvider implements DynamicModelProvider {

    protected static final String DEFAULT_SYSTEM_ORG = "DEFAULT_SYSTEM_ORG";

    protected final Logger log = LoggerFactory.getLogger(getClass());
    protected final ProviderCredentialOperations providerCredentials;

    protected AbstractDynamicModelProvider(ProviderCredentialOperations providerCredentials) {
        this.providerCredentials = providerCredentials;
    }

    /**
     * @summary Resolves the API key for the given model from DB-only sources.
     * @logic ModelEntity.apiKey (per-model override) → ProviderCredential row for
     *     {@code (caller orgId, model.provider)}. Falls back to {@code DEFAULT_SYSTEM_ORG}
     *     when no caller scope is bound (boot-time availability pings, system jobs).
     *     Throws if neither source yields a non-blank key.
     */
    protected String resolveApiKey(ModelEntity model) {
        if (model.getApiKey() != null && !model.getApiKey().isBlank()) {
            return model.getApiKey();
        }
        String orgId = AgentContextHolder.getOrgId();
        String effectiveOrgId = (orgId == null || orgId.isBlank()) ? DEFAULT_SYSTEM_ORG : orgId;
        String provider = model.getProvider();
        return providerCredentials.resolveDefaultKey(effectiveOrgId, provider).orElseThrow(() ->
                new ai.operativus.agentmanager.core.exception.MissingProviderKeyException(provider,
                        "No valid API key configured for provider '" + provider + "' (org '" + effectiveOrgId + "'). " +
                        "Add a key for '" + provider + "' on the Provider Credentials page " +
                        "(or set one directly on the model), then retry."));
    }

    /**
     * @summary Default unsupported implementation for embedding model construction.
     * @logic Concrete strategies that support vector embeddings override this.
     */
    @Override
    public org.springframework.ai.embedding.EmbeddingModel buildEmbeddingModel(ModelEntity modelEntity) {
        throw new UnsupportedOperationException("Embedding configuration and testing is not yet integrated for provider: " + getProviderKeys());
    }
}
