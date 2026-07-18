package ai.operativus.agentmanager.compute.security;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum TaxonomyCategory {
    FINANCIAL,
    MEDICAL,
    IDENTIFICATION,
    BIOMETRIC,
    LOCATION,
    UNCATEGORIZED;

    @JsonCreator
    public static TaxonomyCategory fromString(String value) {
        if (value == null || value.isBlank()) {
            return UNCATEGORIZED;
        }
        try {
            return TaxonomyCategory.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return UNCATEGORIZED;
        }
    }
}
