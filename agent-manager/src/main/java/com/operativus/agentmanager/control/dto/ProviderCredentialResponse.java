package com.operativus.agentmanager.control.dto;

import com.operativus.agentmanager.core.entity.ProviderCredential;

import java.time.LocalDateTime;

/**
 * Domain Responsibility: Outbound DTO for {@link ProviderCredential}. The plaintext
 *     {@code apiKey} is NEVER returned — only a four-character tail preview so admins
 *     can confirm which key is configured without exposing it.
 * State: Immutable record.
 */
public record ProviderCredentialResponse(
        String id,
        String orgId,
        String provider,
        String label,
        String apiKeyPreview,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String createdBy,
        String updatedBy,
        Long version
) {
    public static ProviderCredentialResponse from(ProviderCredential row) {
        return new ProviderCredentialResponse(
                row.getId(),
                row.getOrgId(),
                row.getProvider(),
                row.getLabel(),
                maskTail(row.getApiKey()),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.getCreatedBy(),
                row.getUpdatedBy(),
                row.getVersion()
        );
    }

    private static String maskTail(String key) {
        if (key == null || key.length() < 4) return "****";
        return "****" + key.substring(key.length() - 4);
    }
}
