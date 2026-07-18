package ai.operativus.agentmanager.core.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Semantic risk tier for FinOps budget enforcement. Administrators select a human-readable
 * risk level instead of guessing raw token counts or USD thresholds.
 *
 * <p>The backend translates each tier into concrete enforcement parameters:
 * token budget ceiling, burn-rate multiplier threshold, and model-downgrade eligibility.</p>
 */
public enum FinOpsRiskTier {

    /**
     * Minimal budget enforcement. No hard ceiling, no burn-rate alerts.
     * Suitable for internal dev/test agents.
     */
    UNRESTRICTED(0, null, null, false),

    /**
     * Generous budget with soft alerts. Burn-rate anomaly detection active.
     * Suitable for production agents with predictable workloads.
     */
    LOW_RISK(1, 5_000_000L, 10.0, false),

    /**
     * Moderate budget with hard ceiling. Burn-rate alerts at 5x baseline.
     * Suitable for customer-facing agents with variable load.
     */
    MODERATE_RISK(2, 1_000_000L, 5.0, false),

    /**
     * Strict budget. Hard ceiling, aggressive burn-rate monitoring, model downgrade eligible.
     * Suitable for high-volume or cost-sensitive agents.
     */
    STRICT(3, 500_000L, 3.0, true),

    /**
     * Maximum enforcement. Tight ceiling, requires HITL approval on budget threshold.
     * Suitable for agents handling financial transactions or regulated workloads.
     */
    CRITICAL(4, 100_000L, 2.0, true);

    private final int level;
    private final Long defaultTokenBudget;
    private final Double burnRateMultiplierThreshold;
    private final boolean modelDowngradeEligible;

    FinOpsRiskTier(int level, Long defaultTokenBudget, Double burnRateMultiplierThreshold, boolean modelDowngradeEligible) {
        this.level = level;
        this.defaultTokenBudget = defaultTokenBudget;
        this.burnRateMultiplierThreshold = burnRateMultiplierThreshold;
        this.modelDowngradeEligible = modelDowngradeEligible;
    }

    public int getLevel() { return level; }
    public Long getDefaultTokenBudget() { return defaultTokenBudget; }
    public Double getBurnRateMultiplierThreshold() { return burnRateMultiplierThreshold; }
    public boolean isModelDowngradeEligible() { return modelDowngradeEligible; }

    @JsonValue
    public String toValue() { return name(); }

    @JsonCreator
    public static FinOpsRiskTier fromString(String value) {
        if (value == null || value.isBlank()) {
            return LOW_RISK;
        }
        for (FinOpsRiskTier tier : values()) {
            if (tier.name().equalsIgnoreCase(value) || String.valueOf(tier.level).equals(value)) {
                return tier;
            }
        }
        return LOW_RISK;
    }
}
