package ai.operativus.agentmanager.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import ai.operativus.agentmanager.core.model.enums.OnErrorPolicy;
import ai.operativus.agentmanager.core.model.enums.OnRejectPolicy;
import ai.operativus.agentmanager.core.model.enums.OnTimeoutPolicy;

import java.util.List;

/**
 * Domain Responsibility: REQ-HR-1 — unified human-in-the-loop config struct
 *     attachable to any workflow step, agent, or team member. Replaces (over
 *     the REQ-HR-2..6 migration window) the five disconnected HITL mechanisms
 *     currently in AGM: {@code HitlAdvisor} (tool approval), {@code ApprovalService}
 *     (generic records), {@code RouterSelectorType.HITL} (router branch),
 *     {@code workflow_steps.requires_confirmation} (CONDITION gate), and
 *     {@code workflow_steps.on_reject} (false-branch policy).
 *
 *     <p>Persisted as JSONB under {@code workflow_steps.human_review},
 *     {@code agents.human_review}, and {@code team_members.human_review}. Parsed
 *     back via Jackson at dispatch time.
 *
 *     <p><strong>Field semantics:</strong>
 *     <ul>
 *       <li>{@code requiresConfirmation} — pause BEFORE execute; operator
 *           answers yes/no. Today's {@code workflow_steps.requires_confirmation}
 *           BOOLEAN maps here.</li>
 *       <li>{@code requiresUserInput} — pause BEFORE execute; operator provides
 *           a value to feed forward as input to the dispatched step/agent.</li>
 *       <li>{@code requiresOutputReview} — pause AFTER execute; operator can
 *           approve or modify the produced output.</li>
 *       <li>{@code onReject} — what the dispatcher does when the operator
 *           rejects. Reuses {@link OnRejectPolicy} (SKIP/CANCEL/ELSE_BRANCH).</li>
 *       <li>{@code onTimeout} — what the dispatcher does when {@code timeoutSeconds}
 *           elapses without a decision. {@link OnTimeoutPolicy} default AUTO_REJECT
 *           (safe-by-default per §5 D4 of the unification plan).</li>
 *       <li>{@code onError} — what the dispatcher does when the approval-collection
 *           machinery itself throws. {@link OnErrorPolicy} default CANCEL.</li>
 *       <li>{@code timeoutSeconds} — null = no timeout (operator must explicitly
 *           decide; pending row sits indefinitely). Operator-configurable per row.</li>
 *       <li>{@code approvers} — null/empty = any role-eligible admin can approve;
 *           non-empty = required approver subset by username.</li>
 *       <li>{@code elseStepId} — required when {@code onReject=ELSE_BRANCH};
 *           ignored otherwise. Mirrors today's {@code workflow_steps.else_step_id}.</li>
 *     </ul>
 *
 *     <p>Validation rules live in {@code HumanReviewValidator} (PR-1) — combo
 *     constraints like "elseStepId required when onReject=ELSE_BRANCH" and
 *     "at most one of requiresConfirmation/requiresUserInput/requiresOutputReview
 *     active at a time".
 *
 *     <p>Unknown fields ignored ({@link JsonIgnoreProperties}) so future
 *     additions don't break in-flight pending rows.
 *
 * State: Stateless (Immutable Record carrier)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HumanReview(
        Boolean requiresConfirmation,
        Boolean requiresUserInput,
        Boolean requiresOutputReview,
        OnRejectPolicy onReject,
        OnTimeoutPolicy onTimeout,
        OnErrorPolicy onError,
        Long timeoutSeconds,
        List<String> approvers,
        String elseStepId
) {
    /**
     * Convenience: is any of the three "requires" gates active? Used by
     * dispatchers to short-circuit the pause path when the config is purely
     * policy (e.g. just an {@code onReject} override without a confirmation
     * requirement).
     */
    public boolean isPauseActive() {
        return Boolean.TRUE.equals(requiresConfirmation)
                || Boolean.TRUE.equals(requiresUserInput)
                || Boolean.TRUE.equals(requiresOutputReview);
    }

    /** Defensive accessor: null OnReject defaults to {@link OnRejectPolicy#SKIP}. */
    public OnRejectPolicy effectiveOnReject() {
        return onReject == null ? OnRejectPolicy.SKIP : onReject;
    }

    /** Defensive accessor: null OnTimeout defaults to {@link OnTimeoutPolicy#AUTO_REJECT}. */
    public OnTimeoutPolicy effectiveOnTimeout() {
        return onTimeout == null ? OnTimeoutPolicy.AUTO_REJECT : onTimeout;
    }

    /** Defensive accessor: null OnError defaults to {@link OnErrorPolicy#CANCEL}. */
    public OnErrorPolicy effectiveOnError() {
        return onError == null ? OnErrorPolicy.CANCEL : onError;
    }
}
