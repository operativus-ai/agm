package ai.operativus.agentmanager.core.exception;

/**
 * Domain Responsibility: Signals that a model's LLM provider has no usable API key — neither a
 *     per-model override nor a ProviderCredential for the caller's org (or DEFAULT_SYSTEM_ORG).
 *     Carries the provider name so callers can build an actionable, provider-specific message.
 *
 *     Extends {@link BusinessValidationException} so it maps to a 400 (an operator misconfiguration,
 *     not a server fault) AND so orchestration code can distinguish "no valid key" from other
 *     model-build failures and fail fast — rather than silently substituting a different model
 *     (e.g. the auto-configured bean built from a possibly-invalid env key), which hides the
 *     misconfiguration behind a confusing downstream provider error.
 * State: Immutable.
 */
public class MissingProviderKeyException extends BusinessValidationException {

    private final String provider;

    public MissingProviderKeyException(String provider, String message) {
        super(message);
        this.provider = provider;
    }

    public String getProvider() {
        return provider;
    }
}
