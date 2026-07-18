package com.operativus.agentmanager.control.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Domain Responsibility: Inbound DTO for create / update on
 *   {@link com.operativus.agentmanager.core.entity.KnowledgeBase}.
 *
 *   <p><strong>Mass-assignment fix.</strong> The previous controller bound the raw
 *   {@code KnowledgeBase} JPA entity via {@code @RequestBody}. Because the entity's
 *   id setter is public and Hibernate's {@code @GeneratedValue} only fires when id
 *   is null on insert, an attacker could supply an existing KB's UUID in the
 *   request body and have Spring Data {@code save()} merge → UPDATE the victim's
 *   row, rewriting its name / description / orgId to the attacker's tenant. The
 *   {@code orgId} field on the entity is annotated
 *   {@code @JsonProperty(access = READ_ONLY)} so Jackson dropped the body's orgId
 *   — but the id was unprotected.
 *
 *   <p>This DTO exposes only the fields a client is allowed to set; the controller
 *   creates a new entity (CREATE) or mutates the loaded entity (UPDATE), with id +
 *   orgId always server-managed.
 *
 * State: Immutable record.
 */
public record KnowledgeBaseRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 8192) String description
) {}
