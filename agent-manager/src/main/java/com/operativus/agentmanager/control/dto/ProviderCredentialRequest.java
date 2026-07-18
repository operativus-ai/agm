package com.operativus.agentmanager.control.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Domain Responsibility: Inbound DTO for creating or editing a per-(org, provider) LLM API key.
 *     {@code orgId} is taken from the authenticated caller, never from the body.
 *     {@code apiKey} is encrypted at rest via {@code OutboundApiKeyConverter}.
 *
 *     {@code apiKey} is intentionally NOT {@code @NotBlank}: on the edit path
 *     ({@code PUT /{id}}) a blank/absent key means "keep the stored key" so an admin can
 *     change just the label without re-typing the secret (which the server never returns).
 *     The create path ({@code POST}) still requires a non-blank key — enforced in the
 *     service ({@link com.operativus.agentmanager.control.service.ProviderCredentialService#upsert})
 *     rather than by bean validation so the two paths can share one DTO.
 * State: Immutable record.
 */
public record ProviderCredentialRequest(
        @NotBlank @Size(max = 50) String provider,
        @Size(max = 512) String apiKey,
        @Size(max = 255) String label
) {}
