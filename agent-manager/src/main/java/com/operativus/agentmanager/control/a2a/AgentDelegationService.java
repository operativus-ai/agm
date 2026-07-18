package com.operativus.agentmanager.control.a2a;

import com.operativus.agentmanager.control.finops.model.FinOpsRecords.A2aFinOpsBoundary;
import com.operativus.agentmanager.control.finops.model.FinOpsRecords.ModelValuationRate;
import com.operativus.agentmanager.control.finops.service.LiveValuationEngine;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.model.RunOptions;
import com.operativus.agentmanager.core.model.RunResponse;
import com.operativus.agentmanager.core.registry.AgentOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Domain Responsibility: Serializes A2A FinOps boundary constraints into inter-agent delegation
 * requests, ensuring remote delegate agents cannot exceed the negotiated token ceiling.
 *
 * 2026+ Requirement (Gap 2): The current DelegationTool dispatches tasks without encoding
 * the initiating agent's budget constraints. This service wraps delegation with:
 *   - A computed {@code max_negotiated_tokens} ceiling derived from the remaining budget.
 *   - An {@code A2aFinOpsBoundary} record packed into the task context for the remote agent.
 *
 * If the remote agent exhausts the negotiated ceiling, it must emit a RenegotiationWebhook
 * rather than continuing to bill the initiating agent's budget envelope.
 *
 * Architecture Enforcement:
 * - Constructor Injection (no field/setter injection).
 * - No ApplicationEventPublisher. No reactive streams.
 * - ObjectProvider for AgentOperations to avoid circular dependency at startup.
 *
 * State: Stateless
 */
@Service
public class AgentDelegationService {

    private static final Logger log = LoggerFactory.getLogger(AgentDelegationService.class);

    /**
     * Conservative token-per-USD estimate used to translate remaining budget USD into
     * a max token ceiling for the remote delegate. Uses a mid-range rate (approx gpt-4o-mini output).
     * In production, this should be resolved from LiveValuationEngine using the target agent's model.
     */
    private static final double CONSERVATIVE_TOKENS_PER_USD = 1_500.0;

    /** Safety margin: delegate receives 80% of the remaining budget to reserve headroom for orchestrator. */
    private static final double DELEGATE_BUDGET_FRACTION = 0.80;

    private final ObjectProvider<AgentOperations> agentOperationsProvider;
    private final LiveValuationEngine valuationEngine;

    public AgentDelegationService(
            ObjectProvider<AgentOperations> agentOperationsProvider,
            LiveValuationEngine valuationEngine) {
        this.agentOperationsProvider = agentOperationsProvider;
        this.valuationEngine = valuationEngine;
    }

    /**
     * @summary Delegates a task to a remote agent with strict A2A FinOps boundary constraints.
     * @logic
     * 1. Resolves the initiating agent's remaining budget from the bound AgentContextHolder.
     * 2. Computes the {@code max_negotiated_tokens} ceiling as 80% of the remaining budget
     *    translated to tokens via a conservative USD-to-token rate.
     * 3. Constructs an {@code A2aFinOpsBoundary} record encoding the ceiling for the delegate.
     * 4. Serializes the boundary into the task payload header prepended to the task string.
     * 5. Executes the delegation via AgentOperations and returns the response.
     *
     * @param targetAgentId  The ID of the remote delegate agent.
     * @param task           The task payload to dispatch.
     * @param targetModelId  Optional model ID of the target agent for accurate token estimation.
     * @return               The response content from the delegate agent.
     */
    public String delegateWithFinOpsBoundary(String targetAgentId, String task, String targetModelId) {
        A2aFinOpsBoundary boundary = computeBoundary(targetAgentId, targetModelId);

        log.info("A2A delegation to agent [{}] with max_negotiated_tokens={}, ceiling_usd=${:.4f}",
            targetAgentId, boundary.maxNegotiatedTokens(), boundary.budgetCeilingUsd());

        AgentOperations agentOperations = agentOperationsProvider.getIfAvailable();
        if (agentOperations == null) {
            throw new IllegalStateException("AgentOperations is not available for A2A delegation.");
        }

        String sessionId = AgentContextHolder.getSessionId();
        String userId    = AgentContextHolder.getUserId();
        String orgId     = AgentContextHolder.getOrgId();

        // N-3 Fix: Pass FinOps boundary as structured RunOptions metadata instead of
        // concatenating it into the LLM prompt. The GenAiMetricsAdvisor reads the boundary
        // from RunOptions to enforce the ceiling mid-flight.
        RunOptions options = new RunOptions(null, null, null, null, boundary);

        RunResponse response = agentOperations.run(
            targetAgentId, task, null,
            sessionId, userId, orgId, false, options
        );

        log.info("A2A delegate [{}] completed — session={}", targetAgentId, sessionId);
        return response.content();
    }

    /**
     * @summary Computes the A2A FinOps boundary for a given delegate agent.
     * @logic Resolves remaining budget from control.security.AgentContextHolder and translates
     *        to a token ceiling using the LiveValuationEngine if the target model is known,
     *        or the conservative fallback rate otherwise.
     */
    public A2aFinOpsBoundary computeBoundary(String targetAgentId, String targetModelId) {
        double remainingBudgetUsd = resolveBudgetUsd();
        double delegateBudgetUsd  = remainingBudgetUsd * DELEGATE_BUDGET_FRACTION;

        long maxNegotiatedTokens = computeMaxTokens(delegateBudgetUsd, targetModelId);

        String sessionId = AgentContextHolder.getSessionId();

        return new A2aFinOpsBoundary(
            targetAgentId,
            maxNegotiatedTokens,
            delegateBudgetUsd,
            sessionId
        );
    }

    /**
     * @summary Resolves the initiating agent's remaining budget USD from the bound execution context.
     * @logic Falls back to 0.0 if no AgentContext is bound (no budget constraint applies).
     */
    private double resolveBudgetUsd() {
        try {
            var agentCtx = com.operativus.agentmanager.control.security.AgentContextHolder.getContext();
            return agentCtx.remainingBudget() != null ? agentCtx.remainingBudget() : 0.0;
        } catch (Exception e) {
            log.trace("No AgentContext bound — defaulting remaining budget to 0.0 for A2A boundary.", e);
            return 0.0;
        }
    }

    /**
     * @summary Computes the maximum token ceiling from a USD budget and an optional model ID.
     * @logic Uses LiveValuationEngine's output rate for the target model if known. Falls back to
     *        the conservative rate to prevent over-allocation on unknown models.
     */
    private long computeMaxTokens(double budgetUsd, String targetModelId) {
        if (budgetUsd <= 0.0) return 0L;

        if (targetModelId != null && !targetModelId.isBlank()) {
            // Estimate using output rate only (conservative: output tokens are more expensive)
            ModelValuationRate rate = valuationEngine.resolveRate(targetModelId);

            if (rate != null && rate.outputRatePerKTokens() > 0) {
                double tokensPerUsd = 1_000.0 / rate.outputRatePerKTokens();
                return Math.max(0L, (long) (budgetUsd * tokensPerUsd));
            }
        }

        return Math.max(0L, (long) (budgetUsd * CONSERVATIVE_TOKENS_PER_USD));
    }
}
