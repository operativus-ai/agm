package com.operativus.agentmanager.control.finops.model;

/**
 * Domain Responsibility: Immutable record definitions for the FinOps domain.
 * Provides value objects for multi-tenant chargeback attribution, inter-agent token
 * ceiling negotiation, and administrator-configured token-to-USD conversion rates.
 * State: Immutable Records (Value Objects)
 */
public final class FinOpsRecords {

    private FinOpsRecords() {}

    /**
     * Chargeback attribution tag applied to all telemetry payloads for multi-tenant cost allocation.
     * Every agent execution must carry this tag so financial dashboards can bill specific
     * organizational units rather than absorbing all AI compute centrally.
     *
     * @param orgUnit     Organizational unit responsible for this execution (e.g., "HR", "Engineering").
     * @param projectCode Project or cost-center code for financial reporting (e.g., "Q4-Marketing").
     * @param teamId      Team identifier linking back to the AGM team manifest.
     * @param sessionId   Session identifier enabling per-session cost attribution.
     */
    public record ChargebackTag(
        String orgUnit,
        String projectCode,
        String teamId,
        String sessionId
    ) {}

    /**
     * FinOps boundary constraints serialized into A2A inter-agent delegation requests.
     * Ensures remote delegate agents cannot exceed the negotiated token ceiling, preventing
     * runaway computational debt when tasks are dispatched over inter-process boundaries.
     *
     * @param agentId              Target delegate agent identifier.
     * @param maxNegotiatedTokens  Hard ceiling on total tokens the remote agent may consume.
     * @param budgetCeilingUsd     Maximum USD value authorized for the delegated task.
     * @param initiatingSessionId  Session ID of the parent agent that initiated the delegation.
     */
    public record A2aFinOpsBoundary(
        String agentId,
        long maxNegotiatedTokens,
        double budgetCeilingUsd,
        String initiatingSessionId
    ) {}

    /**
     * Administrator-configured token-to-USD conversion rate for a specific model dimension.
     * Input and output tokens are priced separately following market conventions.
     * Optional discrete rates for cached prompt tokens and reasoning (thinking) tokens are
     * supported for models that break these out separately (e.g., o1, claude-3-7-sonnet).
     * Rates are maintained in the LiveValuationEngine's concurrent cache and hot-reloadable.
     *
     * @param modelId                       Model identifier used as the cache key (e.g., "gpt-4o", "claude-3-5-sonnet").
     * @param inputRatePerKTokens           USD cost per 1,000 input (prompt) tokens.
     * @param outputRatePerKTokens          USD cost per 1,000 output (completion) tokens.
     * @param cachedInputRatePerKTokens     USD cost per 1,000 cached prompt tokens (typically 0.1–0.5× input rate).
     *                                      Zero means "not configured" — cached tokens billed at full input rate.
     * @param reasoningRatePerKTokens       USD cost per 1,000 reasoning (thinking) tokens for o1/thinking models.
     *                                      Zero means "not configured" — reasoning tokens billed at output rate.
     */
    public record ModelValuationRate(
        String modelId,
        double inputRatePerKTokens,
        double outputRatePerKTokens,
        double cachedInputRatePerKTokens,
        double reasoningRatePerKTokens
    ) {
        /** Convenience constructor for backward compatibility when new rates are not configured. */
        public ModelValuationRate(String modelId, double inputRatePerKTokens, double outputRatePerKTokens) {
            this(modelId, inputRatePerKTokens, outputRatePerKTokens, 0.0, 0.0);
        }
    }
}
