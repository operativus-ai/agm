package com.operativus.agentmanager.control.service;

import com.operativus.agentmanager.core.event.AgentRunEvent;
import com.operativus.agentmanager.core.event.AgentRunEventType;
import com.operativus.agentmanager.core.event.AlertFiredEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Domain Responsibility: Bridges {@code AgentRunEventBus} → {@code AlertIntegrationService} for
 *     budget halts. Translates an {@code AgentRunEvent(BUDGET_EXCEEDED)} (timeline event) into
 *     an {@link AlertFiredEvent} so the same webhook fan-out used by metric-driven rules and
 *     approval-SLA breaches also fires when a run is halted mid-flight by
 *     {@code FinOpsBudgetExhaustedException}.
 * State: Stateless.
 *
 * <p>Closes the "money loop" — hops 3→4 of the five-hop chain:
 * <ol>
 *   <li>{@code GenAiMetricsAdvisor} detects cumulative cost &gt; ceiling</li>
 *   <li>{@code AgentRunEvent(BUDGET_EXCEEDED)} published on the bus</li>
 *   <li><b>This bridge</b> listens and translates to {@link AlertFiredEvent}</li>
 *   <li>{@code AlertIntegrationService.onAlertFired} fans out per-org webhooks (async)</li>
 *   <li>External webhook receiver (Slack, PagerDuty, custom) acknowledges</li>
 * </ol>
 */
@Component
public class BudgetExceededAlertBridge {

    public static final String RULE_ID = "BUDGET_EXCEEDED";
    public static final String SEVERITY = "CRITICAL";

    private static final Logger log = LoggerFactory.getLogger(BudgetExceededAlertBridge.class);

    private final ApplicationEventPublisher eventPublisher;

    public BudgetExceededAlertBridge(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @EventListener
    public void onAgentRunEvent(AgentRunEvent event) {
        if (event == null || event.eventType() != AgentRunEventType.BUDGET_EXCEEDED) {
            return;
        }
        String orgId = event.orgId();
        if (orgId == null || orgId.isBlank()) {
            log.warn("BudgetExceededAlertBridge: BUDGET_EXCEEDED event has no orgId — skipping alert dispatch. runId={}",
                    event.runId());
            return;
        }
        try {
            String message = buildMessage(event);
            eventPublisher.publishEvent(new AlertFiredEvent(this, RULE_ID, event.runId(), SEVERITY, message, orgId));
        } catch (RuntimeException ex) {
            log.warn("BudgetExceededAlertBridge: failed to publish AlertFiredEvent runId={} orgId={}",
                    event.runId(), orgId, ex);
        }
    }

    private String buildMessage(AgentRunEvent event) {
        Map<String, Object> payload = event.payload();
        Object cumulative = payload.get("cumulativeUsd");
        Object ceiling = payload.get("budgetCeilingUsd");
        Object modelId = payload.get("modelId");
        Object costKind = payload.get("costKind");
        return "Agent run " + event.runId() + " halted: " + costKind
                + " cost $" + cumulative + " exceeded budget ceiling $" + ceiling
                + " (model=" + modelId + ", agent=" + event.agentId() + ").";
    }
}
