package ai.operativus.agentmanager.core.model;

import java.time.Instant;

/**
 * Domain Responsibility: Wire-format response from {@code POST /api/v1/runs/{runId}/sse-token}.
 *   Returned to the frontend's {@code requestSseToken(runId)} call so it can open
 *   {@code EventSource(`/api/v1/runs/${runId}/events?token=${token}`)} for the live tail.
 * State: Immutable record.
 *
 * <p>{@code expiresAt} is informational — the client uses it to schedule a fresh token request
 * before the current one expires (per OBS-T012 §A.2 reconnect cadence). The server enforces
 * expiry independently inside {@link ai.operativus.agentmanager.control.security.SseTokenStore}.
 */
public record SseTokenResponse(
        String token,
        Instant expiresAt
) {
}
