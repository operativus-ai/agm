package ai.operativus.agentmanager.core.spi;

import ai.operativus.agentmanager.core.entity.HumanReviewPending;
import ai.operativus.agentmanager.core.model.enums.HumanReviewDecision;
import ai.operativus.agentmanager.core.model.enums.HumanReviewSubjectType;

/**
 * Domain Responsibility: REQ-HR-2 SPI seam — pluggable resume strategy per
 *     subject type. {@code HumanReviewService.decide} reads the pending row's
 *     {@code subject_type} and dispatches to the matching handler by
 *     {@link #subjectType()}. Implementations live in compute / control side:
 *
 *     <ul>
 *       <li>{@code WORKFLOW_STEP} — REQ-HR-3 ships a handler that re-enters
 *           {@code WorkflowService} resume.</li>
 *       <li>{@code AGENT_TOOL_CALL} — REQ-HR-4 ships a handler that injects
 *           the operator decision into the Spring AI tool-execution flow.</li>
 *       <li>{@code TEAM_MEMBER_DISPATCH} — future PR ships a handler that
 *           re-enters the team orchestrator.</li>
 *     </ul>
 *
 *     <p>PR-2 ships the SPI only — no implementations yet. The service catches
 *     the "no handler registered" case and logs a WARN rather than throwing,
 *     so the table + decide endpoint can ship before downstream wiring lands.
 *
 * State: Stateless (Spring bean)
 */
public interface HumanReviewResumeHandler {

    /** The {@link HumanReviewSubjectType} this implementation handles. Required for dispatch. */
    HumanReviewSubjectType subjectType();

    /**
     * Apply the operator's decision to the underlying run state. Called by
     * {@code HumanReviewService.decide} after the {@code human_review_pending}
     * row has been updated with the decision.
     *
     * <p>Implementations must be idempotent — the service may retry on
     * transient failures, and the caller may invoke {@code decide} twice on
     * the same row (second call should be a no-op).
     *
     * @param pending the settled pending row (decision already set)
     * @param decision the operator's decision (already on the pending row;
     *     passed explicitly so handlers don't have to re-parse the string column)
     */
    void onDecided(HumanReviewPending pending, HumanReviewDecision decision);
}
