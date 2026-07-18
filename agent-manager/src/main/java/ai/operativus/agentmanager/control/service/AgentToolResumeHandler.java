package ai.operativus.agentmanager.control.service;

import ai.operativus.agentmanager.core.entity.HumanReviewPending;
import ai.operativus.agentmanager.core.model.enums.HumanReviewDecision;
import ai.operativus.agentmanager.core.model.enums.HumanReviewSubjectType;
import ai.operativus.agentmanager.core.spi.HumanReviewResumeHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Domain Responsibility: REQ-HR-4 — {@link HumanReviewResumeHandler}
 *     implementation for {@code subjectType=AGENT_TOOL_CALL}. Bridges unified
 *     HumanReview decisions back to the legacy {@link ApprovalService} so the
 *     existing Approval row settlement (and its downstream agent-run resume
 *     plumbing) fires when the operator hits the unified
 *     {@code /api/v1/approvals/{id}/decide} endpoint (REQ-HR-5).
 *
 *     <p><strong>Bridge semantics:</strong> the {@code HumanReviewPending.id}
 *     is expected to match the legacy {@code Approval.id} when both rows refer
 *     to the same tool-call pause. REQ-HR-4 (this PR) ships the handler that
 *     can settle either side via the unified decide path. REQ-HR-4.5 (future)
 *     wires {@code HitlAdvisor} to create the dual-tracking
 *     {@code HumanReviewPending} row alongside the legacy {@code Approval}
 *     row, completing the consolidation.
 *
 *     <p>Until REQ-HR-4.5 lands, AGENT_TOOL_CALL pending rows are not produced
 *     by any production path — the handler will fire only against tests or
 *     hand-seeded rows. The downstream {@code ApprovalService.resolveApprovalForOrg}
 *     call still works against whatever Approval row exists at the matching id.
 *
 *     <p>{@code ApplicationContext} lazy lookup of {@code ApprovalService}
 *     mirrors the {@code WorkflowStepResumeHandler} pattern — avoids cycles
 *     if {@code ApprovalService} ever needs to publish events that
 *     {@code HumanReviewService} consumes.
 *
 * State: Stateless (Spring bean).
 */
@Component
public class AgentToolResumeHandler implements HumanReviewResumeHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentToolResumeHandler.class);
    // Legacy ApprovalService.resolveApprovalForOrg consumes RunStatus string values
    // ("APPROVED"/"REJECTED"), NOT the HumanReviewDecision enum names ("APPROVE"/"REJECT").
    // RunStatus.fromValue throws IllegalArgumentException on a non-match — surfaced by
    // AgentToolCallHumanReviewE2eRuntimeTest before this fix.
    private static final String LEGACY_APPROVED = "APPROVED";
    private static final String LEGACY_REJECTED = "REJECTED";

    private final ApplicationContext applicationContext;
    private final TransactionTemplate bridgeTxTemplate;

    /**
     * {@code bridgeTxTemplate} uses {@code PROPAGATION_REQUIRES_NEW} to isolate
     * the legacy {@link ApprovalService#resolveApprovalForOrg} call from the
     * outer {@code HumanReviewService.decide} transaction. Without this, when
     * the bridge call throws (e.g. legacy Approval row missing), Spring marks
     * the outer tx rollback-only and the catch below cannot actually swallow —
     * the outer tx fails with {@code UnexpectedRollbackException} and the
     * {@code /decide} call returns 500 instead of settling the pending row.
     */
    public AgentToolResumeHandler(ApplicationContext applicationContext,
                                  PlatformTransactionManager txManager) {
        this.applicationContext = applicationContext;
        this.bridgeTxTemplate = new TransactionTemplate(txManager);
        this.bridgeTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public HumanReviewSubjectType subjectType() {
        return HumanReviewSubjectType.AGENT_TOOL_CALL;
    }

    @Override
    public void onDecided(HumanReviewPending pending, HumanReviewDecision decision) {
        String legacyApprovalId = pending.getId();
        String orgId = pending.getOrgId();
        String resolvedBy = pending.getDecidedBy() != null ? pending.getDecidedBy() : "human-review-bridge";

        String legacyDecision;
        if (decision.isApprove()) {
            legacyDecision = LEGACY_APPROVED;
        } else if (decision.isReject() || decision == HumanReviewDecision.CANCELLED) {
            legacyDecision = LEGACY_REJECTED;
        } else {
            log.warn("AgentToolResumeHandler: unhandled decision {} for pending {}; no-op",
                    decision, legacyApprovalId);
            return;
        }

        final String legacyDecisionFinal = legacyDecision;
        try {
            bridgeTxTemplate.executeWithoutResult(status -> {
                ApprovalService approvalService = applicationContext.getBean(ApprovalService.class);
                approvalService.resolveApprovalForOrg(legacyApprovalId, legacyDecisionFinal, resolvedBy, orgId);
            });
            log.info("AgentToolResumeHandler: bridged decision {} to legacy Approval id={} (org={})",
                    decision, legacyApprovalId, orgId);
        } catch (RuntimeException e) {
            // Legacy Approval row may not exist for an externally-seeded pending row.
            // The REQUIRES_NEW inner tx rolls back independently, so the outer
            // HumanReviewService.decide transaction is unaffected — log + swallow.
            log.warn("AgentToolResumeHandler: legacy Approval bridge failed for pending {} "
                    + "(no matching legacy Approval — outer pending-row settlement is unaffected): {}",
                    legacyApprovalId, e.toString());
        }
    }
}
