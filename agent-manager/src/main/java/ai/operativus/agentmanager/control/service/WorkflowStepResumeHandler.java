package ai.operativus.agentmanager.control.service;

import ai.operativus.agentmanager.control.repository.WorkflowRunRepository;
import ai.operativus.agentmanager.core.entity.HumanReviewPending;
import ai.operativus.agentmanager.core.entity.WorkflowRun;
import ai.operativus.agentmanager.core.model.enums.HumanReviewDecision;
import ai.operativus.agentmanager.core.model.enums.HumanReviewSubjectType;
import ai.operativus.agentmanager.core.spi.HumanReviewResumeHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Domain Responsibility: REQ-HR-3 — {@link HumanReviewResumeHandler}
 *     implementation for {@code subjectType=WORKFLOW_STEP}. Invoked by
 *     {@code HumanReviewService.decide} after a pending row is settled;
 *     applies the operator decision to the underlying {@link WorkflowRun}.
 *
 *     <p><strong>Approve path:</strong> delegates to
 *     {@code WorkflowService.resumeWorkflowRun(runId, currentPayload)} — the
 *     dispatcher pre-set the planned cursor at pause time (mirrors retro §2.5),
 *     so the existing resume loop walks the post-policy step set without any
 *     dispatcher logic duplication.
 *
 *     <p><strong>Reject path:</strong> transitions the run to
 *     {@code RunStatus.CANCELLED} with reason
 *     {@code HUMAN_REVIEW_REJECTED_BY_OPERATOR} via
 *     {@code WorkflowService.cancelWorkflowRun}. Same exit shape as today's
 *     CONDITION + on_reject=CANCEL path so existing FE state-machine logic
 *     doesn't see a new terminal status.
 *
 *     <p><strong>Cancelled / auto-* paths:</strong> AUTO_APPROVED routes to
 *     resume (same as APPROVE); AUTO_REJECTED + CANCELLED route to cancel.
 *
 *     <p><strong>Why ApplicationContext lazy lookup:</strong>
 *     {@code HumanReviewService} sits BELOW {@code WorkflowService} in the
 *     dependency graph (WorkflowService calls pauseFor; resume handler calls
 *     back into WorkflowService). A direct constructor injection forms a
 *     cycle that Spring rejects at startup. The lazy lookup breaks the cycle —
 *     when {@code onDecided} fires, the context is fully initialized and
 *     {@code getBean} resolves in O(1).
 *
 * State: Stateless (Spring bean).
 */
@Component
public class WorkflowStepResumeHandler implements HumanReviewResumeHandler {

    private static final Logger log = LoggerFactory.getLogger(WorkflowStepResumeHandler.class);
    private static final String CANCEL_REASON = "HUMAN_REVIEW_REJECTED_BY_OPERATOR";

    private final ApplicationContext applicationContext;
    private final WorkflowRunRepository workflowRunRepository;

    public WorkflowStepResumeHandler(ApplicationContext applicationContext,
                                     WorkflowRunRepository workflowRunRepository) {
        this.applicationContext = applicationContext;
        this.workflowRunRepository = workflowRunRepository;
    }

    @Override
    public HumanReviewSubjectType subjectType() {
        return HumanReviewSubjectType.WORKFLOW_STEP;
    }

    @Override
    public void onDecided(HumanReviewPending pending, HumanReviewDecision decision) {
        String runId = pending.getRunId();
        WorkflowRun run = workflowRunRepository.findById(runId).orElse(null);
        if (run == null) {
            log.warn("WorkflowStepResumeHandler: run {} not found for pending {}; no-op",
                    runId, pending.getId());
            return;
        }

        if (decision.isApprove()) {
            log.info("WorkflowStepResumeHandler: approving run {} (decision={}, pending={})",
                    runId, decision, pending.getId());
            // Delegate to WorkflowService.resumeWorkflowRun — the dispatcher
            // pre-set the planned cursor at pause time; resume picks up from
            // current_step_order + 1 onwards. Null/empty preCalculatedOutput
            // falls back to run.currentPayload (REQ-DR-6 PR-4 enhancement).
            WorkflowService workflowService = applicationContext.getBean(WorkflowService.class);
            workflowService.resumeWorkflowRun(runId, null);
            return;
        }

        if (decision.isReject() || decision == HumanReviewDecision.CANCELLED) {
            log.info("WorkflowStepResumeHandler: cancelling run {} (decision={}, pending={})",
                    runId, decision, pending.getId());
            WorkflowService workflowService = applicationContext.getBean(WorkflowService.class);
            workflowService.cancelWorkflowRun(runId, CANCEL_REASON);
            return;
        }

        log.warn("WorkflowStepResumeHandler: unhandled decision {} for pending {}; no-op",
                decision, pending.getId());
    }
}
