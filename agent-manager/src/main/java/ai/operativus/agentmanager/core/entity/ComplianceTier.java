package ai.operativus.agentmanager.core.entity;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum ComplianceTier {
    TIER_1_STANDARD(1),
    TIER_2_STRICT(2);

    private final int hierarchy;

    ComplianceTier(int hierarchy) {
        this.hierarchy = hierarchy;
    }

    public int getHierarchy() {
        return hierarchy;
    }

    @JsonCreator
    public static ComplianceTier fromString(String value) {
        if (value == null || value.isBlank()) {
            return TIER_1_STANDARD;
        }
        for (ComplianceTier tier : values()) {
            if (tier.name().equalsIgnoreCase(value) || String.valueOf(tier.hierarchy).equals(value)) {
                return tier;
            }
        }
        return TIER_1_STANDARD;
    }
}
