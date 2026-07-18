package com.operativus.agentmanager.core.model;

/**
 * Domain Responsibility: Wire-format result of an operator-fired AlertIntegration test
 *   dispatch (T040). Captures whether the integration's webhook endpoint accepted the
 *   synthetic payload, the resulting HTTP status code, and a human-readable message —
 *   structured rather than exception-based so the UI can surface latency / error text
 *   without parsing throwables. Mirrors the {@code ModelPingResult} pattern.
 *
 *   <p>{@code statusCode == 0} indicates the request never reached a response (network
 *   failure, timeout, malformed URL, etc.); {@code message} carries the
 *   {@code ExceptionClass: detail} text in that case.</p>
 *
 * State: Immutable record.
 */
public record AlertIntegrationTestResult(
        String integrationId,
        boolean delivered,
        int statusCode,
        String message
) {
}
