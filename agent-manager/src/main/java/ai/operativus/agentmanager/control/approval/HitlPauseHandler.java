package ai.operativus.agentmanager.control.approval;

import ai.operativus.agentmanager.control.finops.exception.FinOpsBudgetExhaustedException;
import ai.operativus.agentmanager.core.model.DecisionPackage;
import ai.operativus.agentmanager.core.registry.ApprovalOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * Domain Responsibility: Routes FinOps budget exhaustion exceptions into the ApprovalRepository
 * HITL pause mechanism via synchronous, directly-injected interface orchestration.
 *
 * Architecture Enforcement:
 * - NO ApplicationEventPublisher async loops (Architectural Check §9).
 * - NO reactive stream doOnNext components (Virtual Thread Supremacy mandate).
 * - Uses explicit Constructor Injection of ApprovalOperations interface (Interface IoC).
 * - Exception handling is synchronous blocking try/catch — the caller throws immediately after.
 * - {@code @Lazy} on ApprovalOperations breaks the startup circular dependency:
 *   approvalService → agentService → agentClientFactory → genAiMetricsAdvisor → hitlPauseHandler → approvalService.
 *   This is safe because the dependency is only invoked at runtime during FinOps exception handling.
 *
 * State: Stateless
 */
@Service
public class HitlPauseHandler {

    private static final Logger log = LoggerFactory.getLogger(HitlPauseHandler.class);

    private final ApprovalOperations approvalOperations;

    public HitlPauseHandler(@Lazy ApprovalOperations approvalOperations) {
        this.approvalOperations = approvalOperations;
    }

    /**
     * @summary Moves an agent execution context into the HITL pause state after a FinOps budget breach.
     * @logic Creates a PENDING Approval record in the ApprovalRepository using the full execution
     *        context from the exception. The caller (GenAiMetricsAdvisor) throws the exception
     *        immediately after this method returns to interrupt the agent loop cleanly.
     *
     *        Called in a synchronous blocking try/catch — no fire-and-forget, no event bus.
     *        This guarantees the HITL record is persisted before the exception propagates,
     *        ensuring zero state leakage between budget breach detection and suspension.
     *
     * @param ex The FinOpsBudgetExhaustedException carrying the full execution context.
     */
    public void pauseForBudgetExhaustion(FinOpsBudgetExhaustedException ex) {
        log.warn("FinOps HITL pause triggered — agent [{}] session [{}]: cumulative ${:.4f} exceeded ceiling ${:.4f}",
            ex.getAgentId(), ex.getSessionId(), ex.getCumulativeUsd(), ex.getBudgetCeilingUsd());

        try {
            String reasoningTrace = String.format(
                "Mid-flight FinOps circuit breaker activated. Cumulative cost $%.4f exceeded budget ceiling " +
                "$%.4f for model [%s]. Inference stream interrupted at boundary.",
                ex.getCumulativeUsd(), ex.getBudgetCeilingUsd(), ex.getModelId());

            String impactAssessment = String.format(
                "Agent [%s] inference stream halted. Session [%s] locked pending human approval. " +
                "FinOps Tier 2 block requires administrator sign-off before execution may resume. " +
                "No tokens were consumed beyond the detected overage in this call.",
                ex.getAgentId(), ex.getSessionId());

            String toolArguments = String.format(
                "{\"cumulativeUsd\": %.4f, \"budgetCeilingUsd\": %.4f, \"modelId\": \"%s\", \"runId\": \"%s\"}",
                ex.getCumulativeUsd(), ex.getBudgetCeilingUsd(),
                ex.getModelId() != null ? ex.getModelId() : "unknown",
                ex.getRunId() != null ? ex.getRunId() : "unknown");

            approvalOperations.createApprovalRequest(
                ex.getRunId() != null ? ex.getRunId() : "UNKNOWN_RUN",
                ex.getSessionId() != null ? ex.getSessionId() : "UNKNOWN_SESSION",
                ex.getAgentId() != null ? ex.getAgentId() : "UNKNOWN_AGENT",
                "FINOPS_BUDGET_CEILING",
                toolArguments,
                "Agent execution paused: FinOps budget ceiling exceeded during active inference. Human approval required to resume.",
                "SYSTEM_FINOPS",
                reasoningTrace,
                impactAssessment,
                DecisionPackage.DecisionTier.TIER_2_FINOPS_BLOCK
            );

            log.info("HITL pause record persisted for FinOps breach — agent [{}], session [{}].",
                ex.getAgentId(), ex.getSessionId());

        } catch (Exception persistenceFailure) {
            log.error("CRITICAL: Failed to persist HITL pause record for FinOps breach. " +
                "Execution context may be inconsistent. Agent [{}], session [{}].",
                ex.getAgentId(), ex.getSessionId(), persistenceFailure);
        }
    }
}
