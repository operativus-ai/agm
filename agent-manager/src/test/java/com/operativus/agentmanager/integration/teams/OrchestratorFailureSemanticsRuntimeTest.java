package com.operativus.agentmanager.integration.teams;

import com.operativus.agentmanager.compute.teams.SequentialOrchestrator;
import com.operativus.agentmanager.compute.teams.SwarmOrchestrator;
import com.operativus.agentmanager.control.repository.AgentRepository;
import com.operativus.agentmanager.core.entity.AgentEntity;
import com.operativus.agentmanager.core.exception.TeamMemberPausedException;
import com.operativus.agentmanager.core.model.AgentStreamEvent;
import com.operativus.agentmanager.core.model.RequiredAction;
import com.operativus.agentmanager.core.model.RunOptions;
import com.operativus.agentmanager.core.model.RunResponse;
import com.operativus.agentmanager.core.model.TenantConstants;
import com.operativus.agentmanager.core.model.definitions.AgentDefinition;
import com.operativus.agentmanager.core.model.definitions.AgentRegistry;
import com.operativus.agentmanager.core.model.enums.RunStatus;
import com.operativus.agentmanager.core.registry.AgentOperations;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModel;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Boot-context runtime coverage of the production failure
 *   semantics of multi-agent orchestrators. The orchestrators have NO built-in
 *   per-worker timeout and NO external cancel API; the only failure-mode
 *   semantics that exist are:
 *
 *   1. <b>Best-effort partial failure (F11/F12)</b> — if a fan-out worker throws
 *      a generic exception, Swarm logs it, surfaces {@code [ERROR: msg]}
 *      inline in the aggregation, and continues returning the other workers'
 *      successful outputs. Sequential is different: it has no try/catch around
 *      the chain step, so a thrown member exception unwinds the whole chain and
 *      the orchestrator never reaches subsequent members.
 *
 *   2. <b>HITL PAUSED fail-all (Tier 2.5 F2)</b> — if any worker returns
 *      {@code RunStatus.PAUSED}, {@link com.operativus.agentmanager.compute.teams.MemberRunGuard}
 *      throws {@link TeamMemberPausedException}; Swarm catches it,
 *      cancels siblings via {@code Future.cancel(true)}, and re-throws so the
 *      team branch in {@code AgentService.run} can lift the child's HITL state
 *      to the team row. Sequential just propagates without sibling cancel
 *      (later members are never invoked because the chain stops).
 *
 *   These tests pin both code paths against two orchestrators (Swarm, Sequential)
 *   on a real Spring + pgvector context, using a
 *   {@code RecordingAgentOperations} runner double that can be scripted to
 *   throw or to return PAUSED on a per-agent basis.
 *
 * State: Stateless. Per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class,
        NoOpReflectionServiceConfig.class})
public class OrchestratorFailureSemanticsRuntimeTest extends BaseIntegrationTest {

    @Autowired private SwarmOrchestrator swarm;
    @Autowired private SequentialOrchestrator sequential;
    @Autowired private AgentRegistry agentRegistry;
    @Autowired private AgentRepository agentRepository;
    @Autowired private FakeChatModel fakeChatModel;

    private RecordingAgentOperations runner;

    @BeforeEach
    void resetHarness() {
        runner = new RecordingAgentOperations();
        fakeChatModel.reset();
        seedDefaultModel();
    }

    /**
     * Case 1 — Swarm best-effort partial failure. Three workers; one throws.
     * Swarm logs the failure, embeds {@code [ERROR: ...]} in the synthesis,
     * and includes the OTHER two workers' successful contents. Final
     * orchestrator return is the aggregated synthesis (no exception thrown).
     */
    @Test
    void swarm_oneWorkerThrows_othersComplete_synthesisEmbedsErrorMarkerInline() {
        String memberA = persistAgent("swarm-a-" + UUID.randomUUID(), "A", true);
        String memberB = persistAgent("swarm-b-" + UUID.randomUUID(), "B", true);
        String memberC = persistAgent("swarm-c-" + UUID.randomUUID(), "C", true);
        String rootId = persistAgentTeam("swarm-root-" + UUID.randomUUID(), "Swarm Root",
                "SWARM", List.of(memberA, memberB, memberC));

        AgentDefinition rootDef = agentRegistry.findById(rootId, TenantConstants.DEFAULT_SYSTEM_ORG);
        assertNotNull(rootDef);

        fakeChatModel.respondWith(("""
                {"rationale":"three-parallel-subtasks",
                 "subtasks":[
                   {"targetAgentId":"%s","specificQuery":"q-A"},
                   {"targetAgentId":"%s","specificQuery":"q-B"},
                   {"targetAgentId":"%s","specificQuery":"q-C"}
                 ]}
                """).formatted(memberA, memberB, memberC));

        runner.scriptedResponses.put(memberA, "A-content");
        runner.scriptedResponses.put(memberC, "C-content");
        runner.throwForAgent.put(memberB, "boom-from-B");

        String output = swarm.execute(rootDef, "request", null,
                "sess-swarm", "user-swarm", TenantConstants.DEFAULT_SYSTEM_ORG, Boolean.TRUE, runner);

        assertAll("swarm partial failure",
                () -> assertTrue(output.contains("A-content"), "successful worker A appears: " + output),
                () -> assertTrue(output.contains("C-content"), "successful worker C appears: " + output),
                () -> assertTrue(output.contains("[ERROR:") && output.contains("boom-from-B"),
                        "failed worker B is surfaced inline as [ERROR: boom-from-B]: " + output),
                () -> assertEquals(3, runner.calls.size(),
                        "all three workers were dispatched (failure happens during execution, not at dispatch)"));
    }

    /**
     * Case 2 — Sequential propagates and stops. Three members; member 2 throws.
     * The orchestrator has NO try/catch around the chain step, so the exception
     * unwinds the whole orchestrator. Members AFTER the failure are never
     * invoked. The exception type is whatever the runner threw.
     */
    @Test
    void sequential_oneWorkerThrows_propagatesExceptionAndSkipsRemaining() {
        String memberA = persistAgent("seq-a-" + UUID.randomUUID(), "A", true);
        String memberB = persistAgent("seq-b-" + UUID.randomUUID(), "B", true);
        String memberC = persistAgent("seq-c-" + UUID.randomUUID(), "C", true);
        String rootId = persistAgentTeam("seq-root-" + UUID.randomUUID(), "Seq Root",
                "SEQUENTIAL", List.of(memberA, memberB, memberC));

        AgentDefinition rootDef = agentRegistry.findById(rootId, TenantConstants.DEFAULT_SYSTEM_ORG);
        assertNotNull(rootDef);

        runner.scriptedResponses.put(memberA, "A-content");
        runner.throwForAgent.put(memberB, "seq-boom");
        runner.scriptedResponses.put(memberC, "C-content"); // would-be content; member never reached

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> sequential.execute(rootDef, "request", null,
                        "sess-seq", "user-seq", TenantConstants.DEFAULT_SYSTEM_ORG, Boolean.TRUE, runner));

        List<String> agentIdsCalled = runner.calls.stream().map(RecordedCall::agentId).toList();
        assertAll("sequential failure stops chain",
                () -> assertTrue(ex.getMessage() != null && ex.getMessage().contains("seq-boom"),
                        "thrown exception preserves the failed member's message: " + ex.getMessage()),
                () -> assertEquals(2, runner.calls.size(),
                        "only members A and B were invoked; C never reached"),
                () -> assertEquals(memberA, agentIdsCalled.get(0),
                        "member A invoked first"),
                () -> assertEquals(memberB, agentIdsCalled.get(1),
                        "member B invoked second (and threw)"),
                () -> assertTrue(!agentIdsCalled.contains(memberC),
                        "member C never invoked"));
    }

    /**
     * Case 3 — Swarm PAUSED fail-all. Three workers; one returns PAUSED. The
     * orchestrator catches TeamMemberPausedException, cancels siblings via
     * Future.cancel(true), and re-throws so the team branch in AgentService
     * can lift the HITL state. Tier 2.5 F2.
     */
    @Test
    void swarm_oneWorkerPaused_failAll_propagatesTeamMemberPausedException() {
        String memberA = persistAgent("swarm-pa-a-" + UUID.randomUUID(), "A", true);
        String memberB = persistAgent("swarm-pa-b-" + UUID.randomUUID(), "B", true);
        String memberC = persistAgent("swarm-pa-c-" + UUID.randomUUID(), "C", true);
        String rootId = persistAgentTeam("swarm-pa-root-" + UUID.randomUUID(), "Swarm Pause Root",
                "SWARM", List.of(memberA, memberB, memberC));

        AgentDefinition rootDef = agentRegistry.findById(rootId, TenantConstants.DEFAULT_SYSTEM_ORG);
        assertNotNull(rootDef);

        fakeChatModel.respondWith(("""
                {"rationale":"three-subtasks-one-pauses",
                 "subtasks":[
                   {"targetAgentId":"%s","specificQuery":"q-A"},
                   {"targetAgentId":"%s","specificQuery":"q-B"},
                   {"targetAgentId":"%s","specificQuery":"q-C"}
                 ]}
                """).formatted(memberA, memberB, memberC));

        runner.scriptedResponses.put(memberA, "A-content");
        runner.pausedFor.add(memberB);
        runner.scriptedResponses.put(memberC, "C-content");

        TeamMemberPausedException ex = assertThrows(TeamMemberPausedException.class,
                () -> swarm.execute(rootDef, "request", null,
                        "sess-swarm-pa", "user-swarm-pa", TenantConstants.DEFAULT_SYSTEM_ORG, Boolean.TRUE, runner));

        assertAll("swarm PAUSED fail-all",
                () -> assertEquals(memberB, ex.getPausedAgentId(),
                        "exception identifies the paused member"),
                () -> assertNotNull(ex.getPausedRunId(),
                        "exception carries the paused run id"),
                () -> assertNotNull(ex.getRequiredAction(),
                        "exception carries the requiredAction payload from member metadata"));
    }

    /**
     * Case 4 — Sequential PAUSED. Member 2 returns PAUSED →
     * MemberRunGuard.requireNotPaused throws → orchestrator does not catch it
     * → propagates. Member 3 is NEVER invoked because the chain stops.
     */
    @Test
    void sequential_oneWorkerPaused_propagatesTeamMemberPausedException_andSkipsRemaining() {
        String memberA = persistAgent("seq-pa-a-" + UUID.randomUUID(), "A", true);
        String memberB = persistAgent("seq-pa-b-" + UUID.randomUUID(), "B", true);
        String memberC = persistAgent("seq-pa-c-" + UUID.randomUUID(), "C", true);
        String rootId = persistAgentTeam("seq-pa-root-" + UUID.randomUUID(), "Seq Pause Root",
                "SEQUENTIAL", List.of(memberA, memberB, memberC));

        AgentDefinition rootDef = agentRegistry.findById(rootId, TenantConstants.DEFAULT_SYSTEM_ORG);
        assertNotNull(rootDef);

        runner.scriptedResponses.put(memberA, "A-content");
        runner.pausedFor.add(memberB);
        runner.scriptedResponses.put(memberC, "C-content"); // never reached

        TeamMemberPausedException ex = assertThrows(TeamMemberPausedException.class,
                () -> sequential.execute(rootDef, "request", null,
                        "sess-seq-pa", "user-seq-pa", TenantConstants.DEFAULT_SYSTEM_ORG, Boolean.TRUE, runner));

        List<String> agentIdsCalled = runner.calls.stream().map(RecordedCall::agentId).toList();
        assertAll("sequential PAUSED",
                () -> assertEquals(memberB, ex.getPausedAgentId(),
                        "exception identifies the paused member B"),
                () -> assertEquals(2, runner.calls.size(),
                        "only A and B invoked; C never reached after the pause"),
                () -> assertEquals(memberA, agentIdsCalled.get(0),
                        "A invoked first"),
                () -> assertEquals(memberB, agentIdsCalled.get(1),
                        "B invoked second (paused)"),
                () -> assertTrue(!agentIdsCalled.contains(memberC),
                        "C is never invoked"));
    }

    private String persistAgent(String id, String name, boolean active) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setOrgId(TenantConstants.DEFAULT_SYSTEM_ORG);
        a.setName(name);
        a.setDescription("failure-semantics fixture: " + name);
        a.setInstructions("failure-semantics fixture instructions");
        a.setModelId("gpt-4o-mini");
        a.setActive(active);
        a.setMaintenanceMode(false);
        return agentRepository.save(a).getId();
    }

    private String persistAgentTeam(String id, String name, String teamMode, List<String> members) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setOrgId(TenantConstants.DEFAULT_SYSTEM_ORG);
        a.setName(name);
        a.setDescription("failure-semantics root: " + name);
        a.setInstructions("failure-semantics root instructions");
        a.setModelId("gpt-4o-mini");
        a.setActive(true);
        a.setMaintenanceMode(false);
        a.setTeam(true);
        a.setTeamMode(teamMode);
        a.setMembers(members);
        return agentRepository.save(a).getId();
    }

    private void seedDefaultModel() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    /**
     * Recording runner double with three controllable behaviors per agentId:
     *   - {@code scriptedResponses}: returns the given content with status COMPLETED
     *   - {@code throwForAgent}: throws RuntimeException(message) when this agent is dispatched
     *   - {@code pausedFor}: returns RunStatus.PAUSED with a synthetic RequiredAction payload
     *     (triggers MemberRunGuard.requireNotPaused → TeamMemberPausedException)
     */
    private static final class RecordingAgentOperations implements AgentOperations {
        private final AtomicInteger seq = new AtomicInteger();
        final Map<String, String> scriptedResponses = new ConcurrentHashMap<>();
        final Map<String, String> throwForAgent = new ConcurrentHashMap<>();
        final java.util.Set<String> pausedFor = ConcurrentHashMap.newKeySet();
        final List<RecordedCall> calls = new CopyOnWriteArrayList<>();

        @Override
        public RunResponse run(String agentId, String userInput, String sessionId) {
            throw new UnsupportedOperationException("minimal run overload not expected");
        }

        @Override
        public RunResponse run(String agentId, String userInput, List<Media> media, String sessionId,
                               String userId, String orgId, Boolean generateFollowups, RunOptions options) {
            calls.add(new RecordedCall(agentId, userInput, media, sessionId, userId, orgId,
                    generateFollowups, options));
            String runId = "run-" + seq.incrementAndGet();
            if (throwForAgent.containsKey(agentId)) {
                throw new RuntimeException(throwForAgent.get(agentId));
            }
            if (pausedFor.contains(agentId)) {
                Map<String, Object> metadata = new HashMap<>();
                // Synthetic RequiredAction — only need a non-null payload for the exception's getter
                metadata.put("requiredAction",
                        RequiredAction.toolApproval("test-tool", "{}", "approval-" + runId,
                                "trace-" + runId, "reasoning-fixture", "dag-fixture"));
                return new RunResponse(runId, sessionId, "[paused]", metadata, new ArrayList<>(),
                        new ArrayList<>(), RunStatus.PAUSED, null);
            }
            String content = scriptedResponses.getOrDefault(agentId, "default-" + agentId);
            return new RunResponse(runId, sessionId, content, new HashMap<>(), new ArrayList<>(),
                    new ArrayList<>(), RunStatus.COMPLETED, null);
        }

        @Override
        public Flux<AgentStreamEvent> stream(String agentId, String userInput, List<Media> media,
                                             String sessionId, String userId, String orgId,
                                             Boolean generateFollowups, RunOptions options) {
            throw new UnsupportedOperationException("stream not expected");
        }

        @Override
        public String runInBackground(String agentId, String userInput, List<Media> media,
                                      String sessionId, String userId, String orgId,
                                      Boolean generateFollowups, RunOptions options) {
            throw new UnsupportedOperationException("runInBackground not expected");
        }

        @Override
        public String runInBackground(String agentId, String userInput, String sessionId) {
            throw new UnsupportedOperationException("runInBackground (minimal) not expected");
        }

        @Override
        public String runPlayground(String agentId, String userInput, String sessionId) {
            throw new UnsupportedOperationException("runPlayground not expected");
        }

        @Override
        public void cancelRun(String runId) {
            throw new UnsupportedOperationException("cancelRun not expected");
        }

        @Override
        public RunResponse continueRun(String runId, String action) {
            throw new UnsupportedOperationException("continueRun not expected");
        }

        @Override
        public void loadKnowledge(String agentId) {
            throw new UnsupportedOperationException("loadKnowledge not expected");
        }
    }

    private record RecordedCall(String agentId, String userInput, List<Media> media, String sessionId,
                                String userId, String orgId, Boolean generateFollowups, RunOptions options) {
    }
}
