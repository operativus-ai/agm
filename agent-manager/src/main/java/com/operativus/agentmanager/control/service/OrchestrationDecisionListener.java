package com.operativus.agentmanager.control.service;

import com.operativus.agentmanager.control.repository.OrchestrationDecisionRepository;
import com.operativus.agentmanager.core.entity.OrchestrationDecisionEntity;
import com.operativus.agentmanager.core.event.AgentRunEvent;
import com.operativus.agentmanager.core.event.AgentRunEventType;
import io.micrometer.context.ContextSnapshotFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Domain Responsibility: Listens for {@link AgentRunEventType#ORCHESTRATOR_DECISION} events on
 *     {@code AgentRunEventBus} and persists a forensic row to {@code orchestration_decisions}.
 *     AGM logging §5.14.
 * State: Stateful (owns a virtual-thread executor for fire-and-forget inserts).
 *
 * <p><b>Why async?</b> {@code AgentRunEventBus} publishes Spring events synchronously — if this
 * listener blocked on a DB round-trip, every Router/Swarm/Coordinator/Planner dispatch would eat
 * one insert of latency before agent work could begin. Risk R-6 mandates virtual-thread
 * fire-and-forget for these inserts; a 10-step Planner run would otherwise stall on 10 sequential
 * writes.
 *
 * <p><b>Failure isolation.</b> A DB failure here never propagates to the event bus: inserts run
 * on a dedicated executor and all exceptions are caught and logged. The primary forensic store
 * for the same event ({@code agent_run_events}) is already durable via the bus itself, so a
 * missed row in this secondary table only costs a convenience index — not correctness.
 */
@Component
public class OrchestrationDecisionListener implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationDecisionListener.class);

    private final OrchestrationDecisionRepository repository;
    private final ExecutorService persistenceExecutor;

    public OrchestrationDecisionListener(OrchestrationDecisionRepository repository) {
        this.repository = repository;
        this.persistenceExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @EventListener
    public void onAgentRunEvent(AgentRunEvent event) {
        if (event == null || event.eventType() != AgentRunEventType.ORCHESTRATOR_DECISION) {
            return;
        }
        try {
            persistenceExecutor.submit(ContextSnapshotFactory.builder().build().captureAll()
                    .wrap(() -> persist(event)));
        } catch (RuntimeException ex) {
            log.warn("OrchestrationDecisionListener: submission failed runId={}", event.runId(), ex);
        }
    }

    private void persist(AgentRunEvent event) {
        try {
            repository.save(toEntity(event));
        } catch (RuntimeException ex) {
            log.warn("OrchestrationDecisionListener: insert failed runId={}", event.runId(), ex);
        }
    }

    OrchestrationDecisionEntity toEntity(AgentRunEvent event) {
        Map<String, Object> payload = event.payload();
        OrchestrationDecisionEntity entity = new OrchestrationDecisionEntity();
        entity.setRunId(event.runId());
        entity.setOrgId(event.orgId());
        entity.setStrategy(resolveStrategy(payload));
        entity.setDecisionType(resolveDecisionType(payload));
        entity.setSelectedAgentId(resolveSelectedAgentId(payload));
        entity.setRationale(asString(payload.get("rationale")));
        entity.setDecisionPayload(payload);
        entity.setCreatedAt(event.eventTs() != null ? event.eventTs() : Instant.now());
        return entity;
    }

    private String resolveStrategy(Map<String, Object> payload) {
        String mode = asString(payload.get("mode"));
        return mode != null ? mode : "UNKNOWN";
    }

    private String resolveDecisionType(Map<String, Object> payload) {
        Object explicit = payload.get("decisionType");
        if (explicit != null) return asString(explicit);
        // All four orchestrators currently emit a single "dispatch" event per invocation.
        // Distinct decision_type values let future emitters (e.g. per-step Planner events)
        // differentiate without changing the schema.
        return "DISPATCH";
    }

    private String resolveSelectedAgentId(Map<String, Object> payload) {
        String explicit = asString(payload.get("targetAgentId"));
        if (explicit != null) return explicit;
        String firstFromList = firstString(payload.get("subtaskAgents"));
        if (firstFromList != null) return firstFromList;
        firstFromList = firstString(payload.get("stepAgents"));
        if (firstFromList != null) return firstFromList;
        firstFromList = firstString(payload.get("memberIds"));
        return firstFromList;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static String firstString(Object value) {
        if (value instanceof List<?> list && !list.isEmpty() && list.get(0) != null) {
            return list.get(0).toString();
        }
        return null;
    }

    @Override
    public void close() {
        persistenceExecutor.shutdown();
    }
}
