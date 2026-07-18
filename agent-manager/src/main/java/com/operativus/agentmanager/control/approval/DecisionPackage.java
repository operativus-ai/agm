package com.operativus.agentmanager.control.approval;

import java.util.List;
import java.util.Map;

/**
 * Domain Responsibility: Represents the structured payload for a Bounded Autonomy decision,
 * enforcing JSON schema structure and rigorously mapping 3-Tier Threshold criteria.
 * State: Immutable Record
 */
public record DecisionPackage(
        String toolDecisionId,
        DecisionTier targetTier,
        ValidationCriteria validationCriteria,
        Map<String, Object> toolPayload,
        String reasoningHash
) {
    public enum DecisionTier {
        WHITELIST(1), // Simple automated approval for low-risk actions
        FINOPS_CHECK(2), // Requires computational cost threshold validation
        CRYPTO_MFA(3); // Requires human-in-the-loop multi-factor cryptographic signature

        private final int level;

        DecisionTier(int level) {
            this.level = level;
        }

        public int getLevel() {
            return level;
        }
    }

    public record ValidationCriteria(
            List<String> allowedScopes,
            Double costThresholdUsd,
            boolean mfaRequired
    ) {}
}
