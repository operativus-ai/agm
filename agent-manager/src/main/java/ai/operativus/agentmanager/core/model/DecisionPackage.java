package ai.operativus.agentmanager.core.model;

import java.time.OffsetDateTime;

/**
 * Immutable audit trail representing a high-consequence agentic decision.
 * Required for NIST FinOps governance standards.
 * Guaranteed to be persisted for compliance auditing on any Hitl interception.
 */
public record DecisionPackage(
    String runId,
    String requestedAgentId,
    String originatingUser,
    String intendedAction,
    String reasoningTrace,
    String impactAssessment,
    DecisionTier tier,
    OffsetDateTime evaluatedAt
) {
    public enum DecisionTier {
        TIER_1_SAFE,          // Can execute automatically
        TIER_2_FINOPS_BLOCK,  // Hits budget envelope; requires manager approval
        TIER_3_DESTRUCTIVE    // System mutation; requires MFA or strict review
    }
}
