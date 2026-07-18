package com.operativus.agentmanager.control.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * Domain Responsibility: Inbound DTO for create / update on
 *   {@link com.operativus.agentmanager.core.entity.AgentCredential}.
 *
 *   <p><strong>Mass-assignment fix.</strong> The previous controller binding accepted
 *   the raw {@code AgentCredential} entity via {@code @RequestBody}, which allowed a
 *   client to set:
 *   <ul>
 *     <li>{@code id} — application-managed string PK. With Spring Data JPA's
 *         {@code save()} merge semantics, supplying an existing id from another
 *         tenant's row turns INSERT into UPDATE on that row, hijacking the
 *         victim's credential record.</li>
 *     <li>{@code agentId} — the controller overrode this from the path variable,
 *         but the bind happened first so the body value briefly populated the
 *         entity before being overwritten.</li>
 *     <li>{@code createdAt} / {@code updatedAt} — JPA-managed timestamps that
 *         should never be client-settable.</li>
 *   </ul>
 *   This DTO exposes ONLY the fields the client is allowed to set; the controller
 *   maps them onto a fresh entity (CREATE) or onto the loaded entity (UPDATE),
 *   with {@code id} always server-generated and {@code agentId} always taken
 *   from the path variable.
 *
 * State: Immutable record.
 */
public record AgentCredentialRequest(
        @NotBlank @Size(max = 50) String credentialType,    // OAUTH2 / API_KEY / JWT / BEARER
        @NotBlank @Size(max = 255) String providerName,
        @Size(max = 8192) String encryptedSecret,
        @Size(max = 4096) String scopes,
        @Size(max = 1024) String tokenEndpoint,
        @Size(max = 255) String clientId,
        LocalDateTime expiresAt,
        boolean enabled
) {}
