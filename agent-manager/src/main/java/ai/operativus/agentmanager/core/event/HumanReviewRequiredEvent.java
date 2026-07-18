package ai.operativus.agentmanager.core.event;

import ai.operativus.agentmanager.core.entity.HumanReviewPending;

/**
 * Domain Responsibility: REQ-HR-2 — published by
 *     {@code HumanReviewService.pauseFor} after a new {@link HumanReviewPending}
 *     row is committed. Subject-specific SSE handlers subscribe to this event
 *     and translate to the right wire format (e.g.
 *     {@code WorkflowWebSocketHandler} broadcasts a {@code HumanReviewRequired}
 *     SSE event scoped to the originating workflow).
 *
 * State: Immutable record carrier.
 */
public record HumanReviewRequiredEvent(HumanReviewPending pending) {}
