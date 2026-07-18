package com.operativus.agentmanager.core.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Domain Responsibility: Wire-format request body for the quarantine, unquarantine, and global
 *   halt-all-runs endpoints on {@link com.operativus.agentmanager.control.controller.IncidentResponseController}.
 *   The {@code reason} field is required so every incident-response action has a forensically
 *   useful operator-supplied annotation in the audit trail.
 * State: Immutable record.
 */
public record QuarantineRequest(
        @NotBlank
        @Size(max = 500)
        String reason
) {
}
