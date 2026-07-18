package ai.operativus.agentmanager.core.model;

import jakarta.validation.constraints.NotBlank;

/**
 * Domain Responsibility: Wire-format request body for {@code POST /api/v1/evaluations/suites}.
 *   Carries the suite name (required), an optional human-readable description, and an
 *   optional creator attribution string. When the FE caller omits {@code createdBy}, the
 *   handler defaults to {@code "system"} — preserved here as a nullable optional rather
 *   than a record-level default to keep the wire shape compatible with the prior
 *   {@code Map<String, String>} contract.
 * State: Immutable record.
 */
public record CreateSuiteRequest(
        @NotBlank String name,
        String description,
        String createdBy
) {
}
