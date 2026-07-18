package ai.operativus.agentmanager.control.dto;

/**
 * Domain Responsibility: Outbound DTO for a provider-credential "test connection" probe.
 *     Mirrors the {@code ModelPingResult} contract: the endpoint always returns 200 OK and
 *     encodes the outcome in {@code success} + {@code message} rather than as an HTTP error,
 *     so the UI can render a rich pass/fail line (with latency) without parsing exception text.
 * State: Immutable record.
 *
 * @param success   true when the key authenticated and the model answered a minimal prompt
 * @param provider  the provider that was probed (echoed for UI convenience)
 * @param model     the model id the key was tested against
 * @param latencyMs round-trip time of the probe in milliseconds
 * @param message   failure detail when {@code success=false}; null on success
 */
public record ProviderCredentialTestResponse(
        boolean success,
        String provider,
        String model,
        long latencyMs,
        String message
) {
    public static ProviderCredentialTestResponse ok(String provider, String model, long latencyMs) {
        return new ProviderCredentialTestResponse(true, provider, model, latencyMs, null);
    }

    public static ProviderCredentialTestResponse fail(String provider, String model, long latencyMs, String message) {
        return new ProviderCredentialTestResponse(false, provider, model, latencyMs, message);
    }
}
