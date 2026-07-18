package com.operativus.agentmanager.integration.teams;

import com.operativus.agentmanager.compute.teams.SwarmOrchestrator;
import com.operativus.agentmanager.control.repository.AgentRepository;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.entity.AgentEntity;
import com.operativus.agentmanager.core.model.AgentStreamEvent;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Boot-context runtime coverage of
 *   {@link com.operativus.agentmanager.compute.teams.SwarmOrchestrator}'s
 *   per-worker context propagation under virtual-thread fan-out. The
 *   orchestrator captures the calling thread's ScopedValue bindings + MDC,
 *   wraps each worker submission via {@link io.micrometer.context.ContextSnapshotFactory},
 *   then rebinds ScopedValues + populates MDC inside each worker before
 *   invoking the runner. These tests pin three invariants of that flow:
 *
 *   1. <b>Parent-context observability</b> — every worker thread observes
 *      the parent's bound {@code currentRunId}, {@code sessionId},
 *      {@code orgId}, {@code userId}, {@code orchestrationDepth} when it
 *      reads via the static {@link AgentContextHolder} accessors.
 *
 *   2. <b>ApprovedTools no-bleed</b> — the orchestrator copies the parent's
 *      approved-tools set into a <i>fresh mutable HashSet per member</i>
 *      ({@code SwarmOrchestrator} line 167-169), so a worker that calls
 *      {@code AgentContextHolder.approveTool(name)} mutates ONLY its own
 *      member-local view. Sibling workers observe the original set; the
 *      parent observes the original set after the fan-out returns.
 *
 *   3. <b>Per-worker fresh telemetry</b> — line 177 of {@code SwarmOrchestrator}
 *      binds a fresh {@code RunTelemetryAccumulator} per worker via
 *      {@code .where(AgentContextHolder.telemetry, new RunTelemetryAccumulator())}.
 *      Each worker observes a non-null telemetry instance distinct from
 *      every other worker's instance — proves the binding is per-call, not
 *      a shared singleton.
 *
 * State: Stateless. Per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}.
 *
 * Implementation notes:
 *   - The recording runner double captures {@link CapturedContext} snapshots
 *     INSIDE the worker thread (during {@code run(...)}), because
 *     {@link AgentContextHolder} accessors read from the current thread's
 *     ScopedValue scope. Snapshots taken post-orchestration would always
 *     be empty.
 *   - Tests wrap the {@code swarm.execute(...)} call in a parent
 *     {@code ScopedValue.where(...).run(...)} carrier so the orchestrator's
 *     parent-context snapshot at lines 133-146 actually observes non-null
 *     values.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class,
        NoOpReflectionServiceConfig.class})
public class OrchestratorContextPropagationRuntimeTest extends BaseIntegrationTest {

    @Autowired private SwarmOrchestrator swarm;
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
     * Case 1 — every worker observes the parent's ScopedValue bindings via
     * {@link AgentContextHolder}'s static accessors. Pins the rebind block
     * at SwarmOrchestrator.java:171-180.
     */
    @Test
    void swarmFanOut_eachWorkerObservesParentScopedValueBindings() {
        String memberA = persistAgent("ctxprop-a-" + UUID.randomUUID(), "A", true, false, null, null);
        String memberB = persistAgent("ctxprop-b-" + UUID.randomUUID(), "B", true, false, null, null);
        String memberC = persistAgent("ctxprop-c-" + UUID.randomUUID(), "C", true, false, null, null);
        String rootId = persistAgent("ctxprop-root-" + UUID.randomUUID(), "Root", true, false,
                "SWARM", List.of(memberA, memberB, memberC));

        AgentDefinition rootDef = agentRegistry.findById(rootId, TenantConstants.DEFAULT_SYSTEM_ORG);
        assertNotNull(rootDef);

        fakeChatModel.respondWith(("""
                {"rationale":"propagation-check",
                 "subtasks":[
                   {"targetAgentId":"%s","specificQuery":"q-A"},
                   {"targetAgentId":"%s","specificQuery":"q-B"},
                   {"targetAgentId":"%s","specificQuery":"q-C"}
                 ]}
                """).formatted(memberA, memberB, memberC));

        runner.scriptedResponses.put(memberA, "A");
        runner.scriptedResponses.put(memberB, "B");
        runner.scriptedResponses.put(memberC, "C");

        String parentRunId = "run-parent-" + UUID.randomUUID();
        String parentSessionId = "sess-parent";
        String parentUserId = "user-parent";
        Integer parentDepth = 7;

        ScopedValue
                .where(AgentContextHolder.currentRunId, parentRunId)
                .where(AgentContextHolder.sessionId, parentSessionId)
                .where(AgentContextHolder.userId, parentUserId)
                .where(AgentContextHolder.orgId, TenantConstants.DEFAULT_SYSTEM_ORG)
                .where(AgentContextHolder.agentId, rootId)
                .where(AgentContextHolder.orchestrationDepth, parentDepth)
                .run(() -> swarm.execute(rootDef, "ignored", null, "sess-swarm",
                        "user-swarm", TenantConstants.DEFAULT_SYSTEM_ORG, Boolean.TRUE, runner));

        assertEquals(3, runner.captures.size(), "all three workers ran and captured");

        for (CapturedContext ctx : runner.captures) {
            assertAll("worker " + ctx.observedAgentTaskId() + " observes parent ScopedValues",
                    () -> assertEquals(parentRunId, ctx.currentRunId(),
                            "currentRunId rebound from parent"),
                    () -> assertEquals(parentUserId, ctx.userId(),
                            "userId rebound from parent (matches userId arg, not parentUserId fallback)"),
                    () -> assertEquals(TenantConstants.DEFAULT_SYSTEM_ORG, ctx.orgId(),
                            "orgId rebound from parent"),
                    () -> assertEquals(parentSessionId, ctx.sessionId(),
                            "sessionId rebound from parent"),
                    () -> assertEquals(parentDepth, ctx.orchestrationDepth(),
                            "orchestrationDepth rebound from parent (Integer, not zero default)"),
                    () -> assertNotNull(ctx.telemetry(),
                            "telemetry ScopedValue is bound (fresh per worker — see Case 3)"));
        }
    }

    /**
     * Case 2 — approvedTools no-bleed. Parent has approvedTools={"tool-x"}.
     * Worker A calls approveTool("tool-y-from-A"). Workers B and C must NOT
     * observe "tool-y-from-A". Verifies the per-member fresh-HashSet copy
     * at SwarmOrchestrator.java:167-169.
     */
    @Test
    void swarmFanOut_approvedToolsMutationOnOneWorkerDoesNotLeakToSiblings() {
        String memberA = persistAgent("apto-a-" + UUID.randomUUID(), "A", true, false, null, null);
        String memberB = persistAgent("apto-b-" + UUID.randomUUID(), "B", true, false, null, null);
        String memberC = persistAgent("apto-c-" + UUID.randomUUID(), "C", true, false, null, null);
        String rootId = persistAgent("apto-root-" + UUID.randomUUID(), "Root", true, false,
                "SWARM", List.of(memberA, memberB, memberC));

        AgentDefinition rootDef = agentRegistry.findById(rootId, TenantConstants.DEFAULT_SYSTEM_ORG);
        assertNotNull(rootDef);

        fakeChatModel.respondWith(("""
                {"rationale":"approved-tools-check",
                 "subtasks":[
                   {"targetAgentId":"%s","specificQuery":"q-A"},
                   {"targetAgentId":"%s","specificQuery":"q-B"},
                   {"targetAgentId":"%s","specificQuery":"q-C"}
                 ]}
                """).formatted(memberA, memberB, memberC));

        runner.scriptedResponses.put(memberA, "A");
        runner.scriptedResponses.put(memberB, "B");
        runner.scriptedResponses.put(memberC, "C");
        // Tell ONLY worker A to mutate approvedTools mid-flight.
        runner.approveToolDuringRun.put(memberA, "tool-y-from-A");

        Set<String> parentApprovedSet = new HashSet<>(Set.of("tool-x"));

        ScopedValue
                .where(AgentContextHolder.currentRunId, "run-parent-" + UUID.randomUUID())
                .where(AgentContextHolder.sessionId, "sess-parent")
                .where(AgentContextHolder.userId, "user-parent")
                .where(AgentContextHolder.orgId, TenantConstants.DEFAULT_SYSTEM_ORG)
                .where(AgentContextHolder.agentId, rootId)
                .where(AgentContextHolder.orchestrationDepth, 1)
                .where(AgentContextHolder.approvedTools, parentApprovedSet)
                .run(() -> {
                    swarm.execute(rootDef, "ignored", null, "sess-swarm",
                            "user-swarm", TenantConstants.DEFAULT_SYSTEM_ORG, Boolean.TRUE, runner);
                });

        assertEquals(3, runner.captures.size());

        CapturedContext ctxA = runner.captureForMember(memberA);
        CapturedContext ctxB = runner.captureForMember(memberB);
        CapturedContext ctxC = runner.captureForMember(memberC);

        assertAll("approvedTools per-worker isolation",
                () -> assertTrue(ctxA.approvedTools().contains("tool-x"),
                        "worker A starts with parent's tool-x"),
                () -> assertTrue(ctxA.approvedTools().contains("tool-y-from-A"),
                        "worker A's approveTool() mutation is visible to itself"),
                () -> assertTrue(ctxB.approvedTools().contains("tool-x"),
                        "worker B starts with parent's tool-x"),
                () -> assertFalse(ctxB.approvedTools().contains("tool-y-from-A"),
                        "worker B does NOT see worker A's mutation (per-member fresh HashSet)"),
                () -> assertTrue(ctxC.approvedTools().contains("tool-x"),
                        "worker C starts with parent's tool-x"),
                () -> assertFalse(ctxC.approvedTools().contains("tool-y-from-A"),
                        "worker C does NOT see worker A's mutation"),
                () -> assertEquals(Set.of("tool-x"), parentApprovedSet,
                        "parent's approvedTools set is NOT mutated by any worker (immutable Set.copyOf snapshot, "
                                + "then per-member fresh HashSet copies)"));
    }

    /**
     * Case 3 — per-worker fresh telemetry binding. Each worker thread observes
     * a non-null {@code RunTelemetryAccumulator} ScopedValue, and every
     * worker's instance is distinct from every other's. Pins
     * SwarmOrchestrator.java:177 (fresh accumulator per worker).
     */
    @Test
    void swarmFanOut_eachWorkerHasFreshDistinctTelemetryAccumulator() {
        String memberA = persistAgent("tel-a-" + UUID.randomUUID(), "A", true, false, null, null);
        String memberB = persistAgent("tel-b-" + UUID.randomUUID(), "B", true, false, null, null);
        String memberC = persistAgent("tel-c-" + UUID.randomUUID(), "C", true, false, null, null);
        String rootId = persistAgent("tel-root-" + UUID.randomUUID(), "Root", true, false,
                "SWARM", List.of(memberA, memberB, memberC));

        AgentDefinition rootDef = agentRegistry.findById(rootId, TenantConstants.DEFAULT_SYSTEM_ORG);
        assertNotNull(rootDef);

        fakeChatModel.respondWith(("""
                {"rationale":"telemetry-isolation-check",
                 "subtasks":[
                   {"targetAgentId":"%s","specificQuery":"q-A"},
                   {"targetAgentId":"%s","specificQuery":"q-B"},
                   {"targetAgentId":"%s","specificQuery":"q-C"}
                 ]}
                """).formatted(memberA, memberB, memberC));

        runner.scriptedResponses.put(memberA, "A");
        runner.scriptedResponses.put(memberB, "B");
        runner.scriptedResponses.put(memberC, "C");

        ScopedValue
                .where(AgentContextHolder.currentRunId, "run-parent-" + UUID.randomUUID())
                .where(AgentContextHolder.sessionId, "sess-parent")
                .where(AgentContextHolder.userId, "user-parent")
                .where(AgentContextHolder.orgId, TenantConstants.DEFAULT_SYSTEM_ORG)
                .where(AgentContextHolder.agentId, rootId)
                .where(AgentContextHolder.orchestrationDepth, 1)
                .run(() -> swarm.execute(rootDef, "ignored", null, "sess-swarm",
                        "user-swarm", TenantConstants.DEFAULT_SYSTEM_ORG, Boolean.TRUE, runner));

        assertEquals(3, runner.captures.size());

        Set<Object> distinctTelemetryInstances = new HashSet<>();
        for (CapturedContext ctx : runner.captures) {
            assertNotNull(ctx.telemetry(),
                    "worker " + ctx.observedAgentTaskId() + " observed telemetry binding");
            distinctTelemetryInstances.add(System.identityHashCode(ctx.telemetry()));
        }

        assertAll("per-worker telemetry isolation",
                () -> assertEquals(3, distinctTelemetryInstances.size(),
                        "each of the 3 workers sees a DISTINCT RunTelemetryAccumulator instance "
                                + "(line 177: fresh `new RunTelemetryAccumulator()` per submission)"));
    }

    private String persistAgent(String id, String name, boolean active, boolean maintenance,
                                String teamMode, List<String> members) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setOrgId(TenantConstants.DEFAULT_SYSTEM_ORG);
        a.setName(name);
        a.setDescription("ctx-propagation fixture: " + name);
        a.setInstructions("ctx-propagation fixture instructions");
        a.setModelId("gpt-4o-mini");
        a.setActive(active);
        a.setMaintenanceMode(maintenance);
        a.setTeam(teamMode != null);
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
     * Recording runner that captures the worker thread's view of
     * {@link AgentContextHolder} at the moment {@code run(...)} is invoked.
     * The capture happens INSIDE the worker thread because ScopedValues are
     * thread-bound.
     */
    private static final class RecordingAgentOperations implements AgentOperations {
        private final AtomicInteger seq = new AtomicInteger();
        final Map<String, String> scriptedResponses = new ConcurrentHashMap<>();
        final Map<String, String> approveToolDuringRun = new ConcurrentHashMap<>();
        final List<CapturedContext> captures = new CopyOnWriteArrayList<>();

        CapturedContext captureForMember(String agentId) {
            return captures.stream()
                    .filter(c -> agentId.equals(c.observedAgentTaskId()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no capture for agent " + agentId));
        }

        @Override
        public RunResponse run(String agentId, String userInput, String sessionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RunResponse run(String agentId, String userInput, List<Media> media, String sessionId,
                               String userId, String orgId, Boolean generateFollowups, RunOptions options) {
            // Optional mid-run mutation: simulate a tool-approval being granted by this worker.
            // Verifies that mutating the per-member approvedTools set does not leak to siblings.
            String toolToApprove = approveToolDuringRun.get(agentId);
            if (toolToApprove != null) {
                AgentContextHolder.approveTool(toolToApprove);
            }

            // Capture the thread's view of every relevant ScopedValue.
            CapturedContext ctx = new CapturedContext(
                    agentId,
                    AgentContextHolder.getCurrentRunId(),
                    AgentContextHolder.getSessionId(),
                    AgentContextHolder.getUserId(),
                    AgentContextHolder.getOrgId(),
                    AgentContextHolder.getOrchestrationDepth(),
                    AgentContextHolder.getTelemetry(),
                    AgentContextHolder.approvedTools.isBound()
                            ? Set.copyOf(AgentContextHolder.approvedTools.get())
                            : Set.of());
            captures.add(ctx);

            String content = scriptedResponses.getOrDefault(agentId, "default-" + agentId);
            return new RunResponse("run-" + seq.incrementAndGet(), sessionId, content,
                    new HashMap<>(), new ArrayList<>(), new ArrayList<>(), RunStatus.COMPLETED, null);
        }

        @Override
        public Flux<AgentStreamEvent> stream(String agentId, String userInput, List<Media> media,
                                             String sessionId, String userId, String orgId,
                                             Boolean generateFollowups, RunOptions options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String runInBackground(String agentId, String userInput, List<Media> media,
                                      String sessionId, String userId, String orgId,
                                      Boolean generateFollowups, RunOptions options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String runInBackground(String agentId, String userInput, String sessionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String runPlayground(String agentId, String userInput, String sessionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void cancelRun(String runId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RunResponse continueRun(String runId, String action) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void loadKnowledge(String agentId) {
            throw new UnsupportedOperationException();
        }
    }

    /** Snapshot of AgentContextHolder accessors as observed by a worker thread. */
    private record CapturedContext(
            String observedAgentTaskId,
            String currentRunId,
            String sessionId,
            String userId,
            String orgId,
            Integer orchestrationDepth,
            Object telemetry,
            Set<String> approvedTools) {
    }
}
