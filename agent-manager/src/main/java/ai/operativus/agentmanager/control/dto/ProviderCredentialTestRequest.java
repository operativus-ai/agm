package ai.operativus.agentmanager.control.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Domain Responsibility: Inbound DTO for a live "test connection" probe against a
 *     provider credential. Validates that a key authenticates and that the chosen
 *     {@code model} is reachable, without persisting anything.
 *
 *     {@code apiKey} is optional: when blank/absent the probe uses the key already
 *     stored for the caller's {@code (org, provider)} — so an admin can re-test a saved
 *     credential without re-typing the secret. When present, that key is tested instead
 *     (e.g. to validate a rotation before saving it). {@code orgId} is derived from the
 *     authenticated caller, never from the body.
 * State: Immutable record.
 */
public record ProviderCredentialTestRequest(
        @NotBlank @Size(max = 50) String provider,
        @Size(max = 512) String apiKey,
        @NotBlank @Size(max = 255) String model
) {}
