package ai.operativus.agentmanager.control.service;

import ai.operativus.agentmanager.control.repository.OrchestrationDecisionRepository;
import ai.operativus.agentmanager.core.entity.OrchestrationDecisionEntity;
import ai.operativus.agentmanager.core.event.AgentRunEvent;
import ai.operativus.agentmanager.core.event.AgentRunEventType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrchestrationDecisionListenerTest {

    @Mock
    private OrchestrationDecisionRepository repository;

    private OrchestrationDecisionListener listener;

    @BeforeEach
    void setUp() {
        listener = new OrchestrationDecisionListener(repository);
    }

    @AfterEach
    void tearDown() {
        listener.close();
    }

    @Test
    void onAgentRunEvent_ignoresNonOrchestratorEventTypes() {
        AgentRunEvent event = new AgentRunEvent(
                AgentRunEventType.LLM_REQUEST,
                "run-1", "agent-a", null, "sess", "org", 0,
                Map.of("mode", "ROUTER"),
                Instant.now());

        listener.onAgentRunEvent(event);

        verifyNoInteractions(repository);
    }

    @Test
    void onAgentRunEvent_ignoresNullEvent() {
        listener.onAgentRunEvent(null);
        verifyNoInteractions(repository);
    }

    @Test
    void toEntity_mapsRouterPayloadWithTargetAgentAndRationale() {
        AgentRunEvent event = new AgentRunEvent(
                AgentRunEventType.ORCHESTRATOR_DECISION,
                "run-1", "root-agent", null, "sess-x", "org-y", 1,
                Map.of(
                        "mode", "ROUTER",
                        "rootAgentId", "root-agent",
                        "targetAgentId", "picked-agent",
                        "rationale", "best fit for intent",
                        "candidateCount", 3),
                Instant.parse("2026-04-23T12:00:00Z"));

        OrchestrationDecisionEntity entity = listener.toEntity(event);

        assertEquals("run-1", entity.getRunId());
        assertEquals("org-y", entity.getOrgId());
        assertEquals("ROUTER", entity.getStrategy());
        assertEquals("DISPATCH", entity.getDecisionType());
        assertEquals("picked-agent", entity.getSelectedAgentId());
        assertEquals("best fit for intent", entity.getRationale());
        assertSame(event.payload(), entity.getDecisionPayload());
        assertEquals(Instant.parse("2026-04-23T12:00:00Z"), entity.getCreatedAt());
    }

    @Test
    void toEntity_mapsSwarmPayloadWithFirstSubtaskAgent() {
        AgentRunEvent event = new AgentRunEvent(
                AgentRunEventType.ORCHESTRATOR_DECISION,
                "run-2", "root-agent", null, null, "org-z", 0,
                Map.of(
                        "mode", "SWARM",
                        "rootAgentId", "root-agent",
                        "subtaskCount", 3,
                        "subtaskAgents", List.of("worker-1", "worker-2", "worker-3"),
                        "rationale", "fan out by capability"),
                Instant.now());

        OrchestrationDecisionEntity entity = listener.toEntity(event);

        assertEquals("SWARM", entity.getStrategy());
        assertEquals("worker-1", entity.getSelectedAgentId());
        assertEquals("fan out by capability", entity.getRationale());
    }

    @Test
    void toEntity_mapsPlannerPayloadWithFirstStepAgentAndNullRationale() {
        AgentRunEvent event = new AgentRunEvent(
                AgentRunEventType.ORCHESTRATOR_DECISION,
                "run-3", "root-agent", null, null, null, 0,
                Map.of(
                        "mode", "PLANNER",
                        "rootAgentId", "root-agent",
                        "stepCount", 2,
                        "stepAgents", List.of("step-agent-a", "step-agent-b")),
                Instant.now());

        OrchestrationDecisionEntity entity = listener.toEntity(event);

        assertEquals("PLANNER", entity.getStrategy());
        assertEquals("step-agent-a", entity.getSelectedAgentId());
        assertNull(entity.getRationale());
    }

    @Test
    void toEntity_mapsCoordinatorPayloadWithFirstMemberAsSelected() {
        AgentRunEvent event = new AgentRunEvent(
                AgentRunEventType.ORCHESTRATOR_DECISION,
                "run-4", "root-agent", null, null, "org-q", 0,
                Map.of(
                        "mode", "COORDINATOR",
                        "memberCount", 2,
                        "memberIds", List.of("member-1", "member-2"),
                        "inputLength", 42),
                Instant.now());

        OrchestrationDecisionEntity entity = listener.toEntity(event);

        assertEquals("COORDINATOR", entity.getStrategy());
        assertEquals("member-1", entity.getSelectedAgentId());
        assertNull(entity.getRationale());
    }

    @Test
    void toEntity_missingModeFallsBackToUnknown() {
        AgentRunEvent event = new AgentRunEvent(
                AgentRunEventType.ORCHESTRATOR_DECISION,
                "run-5", null, null, null, null, null,
                Map.of("rationale", "just because"),
                Instant.now());

        OrchestrationDecisionEntity entity = listener.toEntity(event);

        assertEquals("UNKNOWN", entity.getStrategy());
        assertEquals("DISPATCH", entity.getDecisionType());
        assertNull(entity.getSelectedAgentId());
        assertEquals("just because", entity.getRationale());
    }

    @Test
    void toEntity_usesEventTsForCreatedAt() {
        Instant ts = Instant.parse("2026-01-01T00:00:00Z");
        AgentRunEvent event = new AgentRunEvent(
                AgentRunEventType.ORCHESTRATOR_DECISION,
                "run-6", null, null, null, null, null,
                Map.of("mode", "ROUTER"),
                ts);

        OrchestrationDecisionEntity entity = listener.toEntity(event);

        assertEquals(ts, entity.getCreatedAt());
    }

    @Test
    void toEntity_explicitDecisionTypeOverridesDefault() {
        AgentRunEvent event = new AgentRunEvent(
                AgentRunEventType.ORCHESTRATOR_DECISION,
                "run-7", null, null, null, null, null,
                Map.of("mode", "PLANNER", "decisionType", "PLAN_STEP"),
                Instant.now());

        OrchestrationDecisionEntity entity = listener.toEntity(event);

        assertEquals("PLAN_STEP", entity.getDecisionType());
    }

    @Test
    void onAgentRunEvent_persistsRowAsynchronously() {
        AgentRunEvent event = new AgentRunEvent(
                AgentRunEventType.ORCHESTRATOR_DECISION,
                "run-8", "root", null, "sess", "org", 0,
                Map.of("mode", "ROUTER", "targetAgentId", "picked"),
                Instant.now());

        listener.onAgentRunEvent(event);

        ArgumentCaptor<OrchestrationDecisionEntity> cap = ArgumentCaptor.forClass(OrchestrationDecisionEntity.class);
        verify(repository, timeout(TimeUnit.SECONDS.toMillis(2))).save(cap.capture());
        OrchestrationDecisionEntity saved = cap.getValue();
        assertEquals("run-8", saved.getRunId());
        assertEquals("picked", saved.getSelectedAgentId());
        assertNotNull(saved.getCreatedAt());
    }

    @Test
    void onAgentRunEvent_swallowsRepositoryExceptions() {
        doThrow(new RuntimeException("db down")).when(repository).save(any(OrchestrationDecisionEntity.class));
        AgentRunEvent event = new AgentRunEvent(
                AgentRunEventType.ORCHESTRATOR_DECISION,
                "run-9", null, null, null, null, null,
                Map.of("mode", "ROUTER"),
                Instant.now());

        listener.onAgentRunEvent(event);

        verify(repository, timeout(TimeUnit.SECONDS.toMillis(2)).times(1)).save(any(OrchestrationDecisionEntity.class));
    }
}
