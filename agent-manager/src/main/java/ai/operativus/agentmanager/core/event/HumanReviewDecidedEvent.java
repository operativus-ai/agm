package ai.operativus.agentmanager.core.event;

import ai.operativus.agentmanager.core.entity.HumanReviewPending;
import ai.operativus.agentmanager.core.model.enums.HumanReviewDecision;

/**
 * Domain Responsibility: REQ-HR-2 — published by
 *     {@code HumanReviewService.decide} (or the timeout poller) after a
 *     pending row has been settled. Carries the resolved decision so SSE
 *     handlers can emit a final {@code HumanReviewDecided} wire event and
 *     UIs can clear the pending widget.
 *
 * State: Immutable record carrier.
 */
public record HumanReviewDecidedEvent(HumanReviewPending pending, HumanReviewDecision decision) {}
