package com.operativus.agentmanager.compute.security;

import java.util.UUID;

/**
 * Domain Responsibility: Immutable Data Transfer Object for PII policy CRUD operations.
 * State: Stateless (Immutable Record)
 */
public record PiiPolicyDTO(
        UUID id,
        String name,
        String description,
        PatternType patternType,
        String pattern,
        ScrubStrategy scrubStrategy,
        Boolean enabled,
        TaxonomyCategory taxonomicCategory,
        ComplianceFramework complianceFramework
) {

    /**
     * @summary Converts a JPA entity to its DTO representation.
     */
    public static PiiPolicyDTO fromEntity(PiiPolicyEntity entity) {
        return new PiiPolicyDTO(
                entity.getId(),
                entity.getName(),
                entity.getDescription(),
                entity.getPatternType(),
                entity.getPattern(),
                entity.getScrubStrategy(),
                entity.getEnabled(),
                entity.getTaxonomicCategory(),
                entity.getComplianceFramework()
        );
    }
}
