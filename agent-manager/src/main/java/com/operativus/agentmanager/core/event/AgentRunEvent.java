package com.operativus.agentmanager.core.event;

import java.time.Instant;
import java.util.Map;

/**
 * Domain Responsibility: Immutable payload for a single event in an agent run's timeline.
 *     Produced by orchestrators, advisors, and {@code AgentService}; fanned out by
 *     {@code AgentRunEventBus} to structured log, Spring event publisher, and the
 *     {@code agent_run_events} insert-only audit table.
 * State: Immutable Record (Value Carrier).
 *
 * <p>Fields match {@code agent_run_events} column layout for direct insert. {@code payload}
 * carries event-type-specific structured data serialized as JSON in storage.
 */
public record AgentRunEvent(
        AgentRunEventType eventType,
        String runId,
        String agentId,
        String parentRunId,
        String sessionId,
        String orgId,
        Integer orchestrationDepth,
        Map<String, Object> payload,
        Instant eventTs
) {
    public AgentRunEvent {
        if (eventType == null) throw new IllegalArgumentException("eventType is required");
        if (runId == null) throw new IllegalArgumentException("runId is required");
        if (eventTs == null) eventTs = Instant.now();
        if (payload == null) payload = Map.of();
    }

    public static AgentRunEvent of(AgentRunEventType type, String runId, String agentId, Map<String, Object> payload) {
        return new AgentRunEvent(type, runId, agentId, null, null, null, null, payload, Instant.now());
    }
}
