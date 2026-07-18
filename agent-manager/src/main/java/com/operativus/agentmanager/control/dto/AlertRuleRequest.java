package com.operativus.agentmanager.control.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Domain Responsibility: Inbound DTO for create / update on
 *   {@link com.operativus.agentmanager.core.entity.AlertRule}.
 *
 *   <p><strong>Mass-assignment fix.</strong> The previous controller bound
 *   {@code @RequestBody AlertRule} — the raw JPA entity. {@code AlertRule.id} is
 *   application-managed ({@code @Id private String id} with no
 *   {@code @GeneratedValue}); the service only generated a UUID when the body's
 *   id was null/blank. A caller in org A could POST {"id":"&lt;victim-rule-id&gt;", ...}
 *   and have {@code save()} merge → UPDATE the victim's rule row, rewriting it
 *   with the attacker's threshold / condition / orgId. The {@code orgId} field on
 *   the entity carries {@code @JsonProperty(access=READ_ONLY)} and the service
 *   overwrites it post-bind, but the {@code id} was unprotected at both layers.
 *
 *   <p>Concrete impact: an attacker who learned a victim's rule id could (a) DoS
 *   the victim's alerting by setting {@code threshold=0} (constant fires) or
 *   {@code threshold=Double.MAX} (never fires), (b) silently transfer ownership
 *   of the rule to their own org by relying on the service's post-bind
 *   {@code setOrgId} call, (c) re-target the rule at a different metric to hide
 *   the victim's real alerts.
 *
 *   <p>This DTO exposes only the safe-field subset; the controller builds a fresh
 *   entity (id always null → service UUID-generates).
 *
 * State: Immutable record.
 */
public record AlertRuleRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 4096) String description,
        @NotBlank @Size(max = 255) String metricName,
        @NotBlank
        @Pattern(regexp = "GT|GTE|LT|LTE|EQ",
                message = "condition must be one of GT, GTE, LT, LTE, EQ")
        String condition,
        double threshold,
        @PositiveOrZero int windowSeconds,
        @NotBlank
        @Pattern(regexp = "INFO|WARNING|CRITICAL",
                message = "severity must be one of INFO, WARNING, CRITICAL")
        String severity,
        boolean enabled,
        @Size(max = 255) String notificationChannel
) {}
