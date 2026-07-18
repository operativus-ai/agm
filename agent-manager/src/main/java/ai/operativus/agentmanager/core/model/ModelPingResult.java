package ai.operativus.agentmanager.core.model;

/**
 * Domain Responsibility: Wire-format result of a manual model liveness ping (operator-
 *   fired check via {@code POST /api/v1/models/{id}/test}). Structured rather than
 *   exception-based so the UI can surface latency and error text without parsing
 *   exception messages.
 * State: Immutable record.
 *
 * <p>{@code available=true} means the provider responded successfully within the call
 * window. {@code available=false} carries an {@code errorMessage} explaining the failure
 * (provider exception text, network timeout, missing API key, etc.).
 */
public record ModelPingResult(
        String modelId,
        boolean available,
        long latencyMs,
        String errorMessage
) {
}
