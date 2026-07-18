package com.operativus.agentmanager.compute.security;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum ComplianceFramework {
    HIPAA,
    PCI_DSS,
    GDPR,
    CCPA,
    STANDARD;

    @JsonCreator
    public static ComplianceFramework fromString(String value) {
        if (value == null || value.isBlank()) {
            return STANDARD;
        }
        try {
            return ComplianceFramework.valueOf(value.toUpperCase().replace("-", "_"));
        } catch (IllegalArgumentException e) {
            return STANDARD;
        }
    }
}
