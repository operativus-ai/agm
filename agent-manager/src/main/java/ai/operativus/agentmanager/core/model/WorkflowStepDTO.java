package ai.operativus.agentmanager.core.model;

import java.time.LocalDateTime;

/**
 * Domain Responsibility: Acts as an immutable data transfer object defining an individual, ordered step or action within a broader Workflow.
 * State: Stateless (Immutable Record carrier)
 *
 * <p>{@code routerConfig} is populated only for {@code ROUTER} steps (REQ-DR-4)
 * and is {@code null} for every other step type.
 *
 * <p>{@code onReject} (REQ-DR-6 PR-2/3) applies only to {@code CONDITION} steps —
 * null/SKIP preserves the skip-next-step default; "CANCEL" transitions the run
 * to CANCELLED on a false evaluation; "ELSE_BRANCH" jumps to {@code elseStepId}.
 * Soft-deprecated by {@code humanReview.onReject} (REQ-HR-1); both readable
 * during the REQ-HR-3..6 migration window.
 *
 * <p>{@code elseStepId} (REQ-DR-6 PR-3) is the target step for the
 * {@code ELSE_BRANCH} policy and {@code null} otherwise. Soft-deprecated by
 * {@code humanReview.elseStepId}.
 *
 * <p>{@code requiresConfirmation} (REQ-DR-6 PR-4) — when true on a CONDITION
 * step, the dispatcher pauses the run with the planned cursor (resolved policy)
 * and waits for operator approval via the existing {@code /resume} endpoint.
 * Null treated as false. Disallowed with {@code on_reject=CANCEL}. Soft-
 * deprecated by {@code humanReview.requiresConfirmation}.
 *
 * <p>{@code humanReview} (REQ-HR-1) is the unified HumanReview config that
 * consolidates the three soft-deprecated fields above plus adds new HumanReview
 * primitives (requiresUserInput, requiresOutputReview, onTimeout, onError,
 * timeoutSeconds, approvers). Dispatcher integration lands in REQ-HR-3.
 */
public record WorkflowStepDTO(
        String id,
        String workflowId,
        Integer stepOrder,
        String agentId,
        String action,
        RouterStepConfig routerConfig,
        String onReject,
        String elseStepId,
        Boolean requiresConfirmation,
        HumanReview humanReview,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
