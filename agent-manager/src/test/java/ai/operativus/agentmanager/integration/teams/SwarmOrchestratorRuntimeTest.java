package ai.operativus.agentmanager.integration.teams;

import ai.operativus.agentmanager.compute.teams.SwarmOrchestrator;
import ai.operativus.agentmanager.control.repository.AgentRepository;
import ai.operativus.agentmanager.core.entity.AgentEntity;
import ai.operativus.agentmanager.core.exception.BusinessValidationException;
import ai.operativus.agentmanager.core.model.TenantConstants;
import ai.operativus.agentmanager.core.model.AgentStreamEvent;
import ai.operativus.agentmanager.core.model.RunOptions;
import ai.operativus.agentmanager.core.model.RunResponse;
import ai.operativus.agentmanager.core.model.definitions.AgentDefinition;
import ai.operativus.agentmanager.core.model.definitions.AgentRegistry;
import ai.operativus.agentmanager.core.model.enums.RunStatus;
import ai.operativus.agentmanager.core.registry.AgentOperations;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModel;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import ai.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Direct runtime coverage of
 *   {@link ai.operativus.agentmanager.compute.teams.SwarmOrchestrator} — the
 *   structured-fan-out strategy. The orchestrator LLM produces a list of
 *   {@code (targetAgentId, specificQuery)} subtasks; workers run concurrently
 *   on virtual-thread tasks; results aggregate into a Markdown synthesis.
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 *
 * Cases mapped to {@code docs/testing/agm-runtime-testing-spec.md} T030 (2 cases).
 *
 * Implementation notes / gaps these tests pin:
 *   - The Swarm's internal {@code ChatClient} comes from the Spring-provided
 *     {@code ChatClient.Builder}, which in tests resolves to the FakeChatModel
 *     builder. Scripting ONE JSON {@link SwarmOrchestrator.OrchestratorResponse}
 *     response drives {@code .entity(OrchestratorResponse.class)} through
 *     Spring AI's {@code BeanOutputConverter}.
 *   - Each worker runs on a virtual-thread task; runner calls are gathered into
 *     a thread-safe {@code CopyOnWriteArrayList} and asserted by count + id set
 *     (NOT by order, since parallel scheduling is nondeterministic).
 *   - {@code generateFollowups} is HARD-CODED to {@code false} on every worker
 *     (line 112 of {@link SwarmOrchestrator}).
 *   - Branch session ids are derived via {@code OrchestrationMemoryScopes.memberConversationId}.
 *     For agents with {@code isolateMemory=false} (default), this returns the bare session id
 *     unchanged. Tests assert equality with the caller's session id.
 *   - Empty-members guard returns the string "Team has no members." (NO throw).
 *   - All-filtered-out (e.g., all inactive, or every member id equals root) →
 *     {@link BusinessValidationException}("Swarm failed: No valid, active
 *     members found.").
 *   - The root agent id itself is EXCLUDED from worker candidates (line 67). A
 *     self-referencing root with only itself as a member thus trips the
 *     all-filtered-out guard, NOT the empty-members guard.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class SwarmOrchestratorRuntimeTest extends BaseIntegrationTest {

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
     * T030 case 1 — happy-path fan-out.
     *
     * The Swarm LLM emits three subtasks (one per active member). Each worker
     * runs on a virtual thread; all must execute, with matching ids, with
     * generateFollowups=false, and with branch session ids derived from the
     * caller's session id.
     */
    @Test
    void execute_withActiveMembers_fanOutsConcurrentlyAndAggregatesResults() {
        String memberA = persistAgent("swarm-a-" + UUID.randomUUID(), "Worker A", true, false, null, null);
        String memberB = persistAgent("swarm-b-" + UUID.randomUUID(), "Worker B", true, false, null, null);
        String memberC = persistAgent("swarm-c-" + UUID.randomUUID(), "Worker C", true, false, null, null);
        String rootId = persistAgent("swarm-root-" + UUID.randomUUID(), "Swarm Root", true, false,
                "SWARM", List.of(memberA, memberB, memberC));

        AgentDefinition rootDef = agentRegistry.findById(rootId, TenantConstants.DEFAULT_SYSTEM_ORG);
        assertNotNull(rootDef);

        // Script: one structured OrchestratorResponse with three subtasks
        fakeChatModel.respondWith(("""
                {"rationale":"three-parallel-subtasks",
                 "subtasks":[
                   {"targetAgentId":"%s","specificQuery":"subtask for A"},
                   {"targetAgentId":"%s","specificQuery":"subtask for B"},
                   {"targetAgentId":"%s","specificQuery":"subtask for C"}
                 ]}
                """).formatted(memberA, memberB, memberC));

        // All three workers return distinct content
        runner.scriptedResponses.put(memberA, "A result");
        runner.scriptedResponses.put(memberB, "B result");
        runner.scriptedResponses.put(memberC, "C result");

        String output = swarm.execute(rootDef, "complex multi-part request", null,
                "sess-swarm", "user-swarm", TenantConstants.DEFAULT_SYSTEM_ORG, Boolean.TRUE, runner);

        assertAll("swarm synthesis + fan-out correctness",
                () -> assertTrue(output.contains("### Swarm Synthesis"),
                        "aggregated output must carry the Swarm Synthesis header"),
                () -> assertTrue(output.contains("three-parallel-subtasks"),
                        "aggregated output must carry the orchestrator rationale"),
                () -> assertTrue(output.contains("A result") && output.contains("B result") && output.contains("C result"),
                        "aggregated output must embed all three worker contents"),
                () -> assertEquals(3, runner.calls.size(),
                        "runner invoked once per subtask"),
                () -> assertTrue(runner.agentIdsCalled().containsAll(List.of(memberA, memberB, memberC)),
                        "all three member ids were invoked (order non-deterministic under virtual threads)"),
                () -> assertTrue(runner.calls.stream().allMatch(c -> Boolean.FALSE.equals(c.generateFollowups())),
                        "generateFollowups is HARD-CODED to false on every worker (line 112)"),
                () -> assertTrue(runner.calls.stream().allMatch(c -> "sess-swarm".equals(c.sessionId())),
                        "with isolateMemory=false (default), branch session id is the bare caller session id"),
                () -> assertTrue(runner.calls.stream().allMatch(c -> "user-swarm".equals(c.userId())),
                        "userId threads through to every worker unchanged"),
                () -> assertTrue(runner.calls.stream().allMatch(c -> TenantConstants.DEFAULT_SYSTEM_ORG.equals(c.orgId())),
                        "orgId threads through to every worker unchanged"),
                () -> assertEquals(1, fakeChatModel.receivedPrompts().size(),
                        "orchestrator LLM is consulted exactly once (single decomposition)"));
    }

    /**
     * F7 LLM-hallucinated-agent-id rejection. The Swarm orchestrator's plan references a
     * targetAgentId that is NOT in the team's resolved member set (the LLM hallucinated a
     * plausible-looking id). The orchestrator MUST throw
     * {@link ai.operativus.agentmanager.core.exception.BusinessValidationException}
     * carrying the "non-member agent" message BEFORE dispatching any worker, so no orphan
     * child run rows can be persisted. Reference: SwarmOrchestrator.java:152-156 (F7).
     */
    @Test
    void execute_subtaskReferencesAgentIdNotInMemberSet_throwsBusinessValidationException_andDispatchesNoWorkers() {
        String memberA = persistAgent("swarm-f7-a-" + UUID.randomUUID(), "A", true, false, null, null);
        String memberB = persistAgent("swarm-f7-b-" + UUID.randomUUID(), "B", true, false, null, null);
        // A persisted-but-NOT-in-team agent — the LLM hallucinates this id into a subtask.
        String orphanAgent = persistAgent("swarm-f7-orphan-" + UUID.randomUUID(), "Orphan", true, false, null, null);

        String rootId = persistAgent("swarm-f7-root-" + UUID.randomUUID(), "Swarm F7 Root", true, false,
                "SWARM", List.of(memberA, memberB));
        AgentDefinition rootDef = agentRegistry.findById(rootId, TenantConstants.DEFAULT_SYSTEM_ORG);
        assertNotNull(rootDef);

        // Two subtasks: one in-team (memberA), one referencing orphanAgent. F7 enforcement
        // iterates the subtasks list and throws BEFORE submitting any task to the executor.
        fakeChatModel.respondWith(("""
                {"rationale":"hallucinated-id-test",
                 "subtasks":[
                   {"targetAgentId":"%s","specificQuery":"q-A"},
                   {"targetAgentId":"%s","specificQuery":"q-orphan"}
                 ]}
                """).formatted(memberA, orphanAgent));

        ai.operativus.agentmanager.core.exception.BusinessValidationException ex =
                org.junit.jupiter.api.Assertions.assertThrows(
                        ai.operativus.agentmanager.core.exception.BusinessValidationException.class,
                        () -> swarm.execute(rootDef, "request", null, "sess-f7-sw", "user-f7-sw",
                                TenantConstants.DEFAULT_SYSTEM_ORG, Boolean.TRUE, runner));

        assertAll("swarm F7 hallucinated-id rejection",
                () -> assertTrue(ex.getMessage().contains("non-member agent"),
                        "exception identifies non-member agent rejection: " + ex.getMessage()),
                () -> assertTrue(ex.getMessage().contains(orphanAgent),
                        "exception names the offending agent id: " + ex.getMessage()),
                () -> assertEquals(1, runner.calls.size(),
                        "F7 check fires PER subtask inside the loop. Subtask 1 (memberA) passes F7 + "
                                + "is submitted to the executor. The throw happens on subtask 2 (orphan) "
                                + "BEFORE its executor.submit. The try-with-resources executor.close() blocks "
                                + "until memberA's already-submitted task finishes — hence 1 recorded call."),
                () -> assertEquals(memberA, runner.calls.get(0).agentId(),
                        "the only dispatched task is the in-team memberA from subtask 1"),
                () -> assertEquals(1, fakeChatModel.receivedPrompts().size(),
                        "orchestrator LLM consulted exactly once; the throw happens during iteration"));
    }

    /**
     * T030 case 2 — guard matrix. Three sub-paths covered via assertAll:
     *   (a) empty members list → {@link BusinessValidationException}
     *       ("Swarm failed: No members configured.")
     *   (b) members exist but all are inactive → {@link BusinessValidationException}
     *       ("Swarm failed: No valid, active members found.")
     *   (c) orchestrator LLM returns an empty subtasks array → STRING
     *       "Swarm Orchestrator determined no subtasks were required." (NO throw).
     */
    @Test
    void execute_guardMatrix_emptyMembersInactiveAllAndEmptySubtasks() {
        assertAll("swarm guard matrix",
                this::assertEmptyMembersThrowsBusinessValidation,
                this::assertAllInactiveThrowsBusinessValidation,
                this::assertEmptySubtasksReturnsStringNoThrow);
    }

    private void assertEmptyMembersThrowsBusinessValidation() {
        runner = new RecordingAgentOperations();
        fakeChatModel.reset();

        String rootId = persistAgent("swarm-empty-" + UUID.randomUUID(), "Empty Swarm", true, false,
                "SWARM", List.of());
        AgentDefinition rootDef = agentRegistry.findById(rootId, TenantConstants.DEFAULT_SYSTEM_ORG);
        assertNotNull(rootDef);

        BusinessValidationException ex = assertThrows(BusinessValidationException.class,
                () -> swarm.execute(rootDef, "anything", null, "sess-x", "user-x", TenantConstants.DEFAULT_SYSTEM_ORG,
                        Boolean.FALSE, runner));
        // SwarmOrchestrator collapsed the empty-members and all-inactive guards into one
        // message ("Swarm failed: No valid, active members found." — SwarmOrchestrator:82).
        assertTrue(ex.getMessage().contains("No valid, active members found"),
                "empty members must throw BusinessValidationException with 'No valid, active members found': " + ex.getMessage());
        assertEquals(0, runner.calls.size());
        assertEquals(0, fakeChatModel.receivedPrompts().size(),
                "LLM must not be consulted when member list is empty");
    }

    private void assertAllInactiveThrowsBusinessValidation() {
        runner = new RecordingAgentOperations();
        fakeChatModel.reset();

        String inA = persistAgent("swarm-inactive-a-" + UUID.randomUUID(), "Inactive A", false, false, null, null);
        String inB = persistAgent("swarm-inactive-b-" + UUID.randomUUID(), "Inactive B", false, false, null, null);
        String rootId = persistAgent("swarm-allinactive-" + UUID.randomUUID(), "All Inactive Swarm", true, false,
                "SWARM", List.of(inA, inB));
        AgentDefinition rootDef = agentRegistry.findById(rootId, TenantConstants.DEFAULT_SYSTEM_ORG);
        assertNotNull(rootDef);

        BusinessValidationException ex = assertThrows(BusinessValidationException.class,
                () -> swarm.execute(rootDef, "anything", null, "sess-y", "user-y", TenantConstants.DEFAULT_SYSTEM_ORG,
                        Boolean.FALSE, runner));
        assertTrue(ex.getMessage().contains("No valid, active members"),
                "all-inactive must throw BusinessValidationException with 'No valid, active members': " + ex.getMessage());
        assertEquals(0, runner.calls.size());
        assertEquals(0, fakeChatModel.receivedPrompts().size(),
                "LLM must not be consulted when no valid workers remain after filtering");
    }

    private void assertEmptySubtasksReturnsStringNoThrow() {
        runner = new RecordingAgentOperations();
        fakeChatModel.reset();

        String m = persistAgent("swarm-sole-" + UUID.randomUUID(), "Sole Worker", true, false, null, null);
        String rootId = persistAgent("swarm-emptysubs-" + UUID.randomUUID(), "Empty Subtasks Swarm", true, false,
                "SWARM", List.of(m));
        AgentDefinition rootDef = agentRegistry.findById(rootId, TenantConstants.DEFAULT_SYSTEM_ORG);
        assertNotNull(rootDef);

        fakeChatModel.respondWith("{\"rationale\":\"nothing to do\",\"subtasks\":[]}");

        String output = swarm.execute(rootDef, "anything", null, "sess-z", "user-z", TenantConstants.DEFAULT_SYSTEM_ORG,
                Boolean.FALSE, runner);

        assertEquals("Swarm Orchestrator determined no subtasks were required.", output,
                "empty subtasks returns the guard string VERBATIM (no throw)");
        assertEquals(0, runner.calls.size(),
                "no subtasks means no worker invocations");
        assertEquals(1, fakeChatModel.receivedPrompts().size(),
                "orchestrator LLM IS consulted once; no workers follow");
    }

    private String persistAgent(String id, String name, boolean active, boolean maintenance,
                                String teamMode, List<String> members) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setOrgId(TenantConstants.DEFAULT_SYSTEM_ORG);
        a.setName(name);
        a.setDescription("T030 fixture: " + name);
        a.setInstructions("T030 fixture instructions");
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

    private static final class RecordingAgentOperations implements AgentOperations {
        private final AtomicInteger seq = new AtomicInteger();
        final java.util.Map<String, String> scriptedResponses = new java.util.concurrent.ConcurrentHashMap<>();
        final java.util.List<RecordedCall> calls = new java.util.concurrent.CopyOnWriteArrayList<>();

        List<String> agentIdsCalled() {
            return calls.stream().map(RecordedCall::agentId).toList();
        }

        @Override
        public RunResponse run(String agentId, String userInput, String sessionId) {
            throw new UnsupportedOperationException("minimal run overload not expected in T030");
        }

        @Override
        public RunResponse run(String agentId, String userInput, List<Media> media, String sessionId,
                                String userId, String orgId, Boolean generateFollowups, RunOptions options) {
            calls.add(new RecordedCall(agentId, userInput, media, sessionId, userId, orgId,
                    generateFollowups, options));
            String content = scriptedResponses.getOrDefault(agentId, "default-content-for-" + agentId);
            return new RunResponse("run-" + seq.incrementAndGet(), sessionId, content,
                    new HashMap<>(), new ArrayList<>(), new ArrayList<>(), RunStatus.COMPLETED, null);
        }

        @Override
        public Flux<AgentStreamEvent> stream(String agentId, String userInput, List<Media> media,
                                              String sessionId, String userId, String orgId,
                                              Boolean generateFollowups, RunOptions options) {
            throw new UnsupportedOperationException("stream not expected in T030");
        }

        @Override
        public String runInBackground(String agentId, String userInput, List<Media> media,
                                       String sessionId, String userId, String orgId,
                                       Boolean generateFollowups, RunOptions options) {
            throw new UnsupportedOperationException("runInBackground not expected in T030");
        }

        @Override
        public String runInBackground(String agentId, String userInput, String sessionId) {
            throw new UnsupportedOperationException("runInBackground (minimal) not expected in T030");
        }

        @Override
        public String runPlayground(String agentId, String userInput, String sessionId) {
            throw new UnsupportedOperationException("runPlayground not expected in T030");
        }

        @Override
        public void cancelRun(String runId) {
            throw new UnsupportedOperationException("cancelRun not expected in T030");
        }

        @Override
        public RunResponse continueRun(String runId, String action) {
            throw new UnsupportedOperationException("continueRun not expected in T030");
        }

        @Override
        public void loadKnowledge(String agentId) {
            throw new UnsupportedOperationException("loadKnowledge not expected in T030");
        }
    }

    private record RecordedCall(String agentId, String userInput, List<Media> media, String sessionId,
                                 String userId, String orgId, Boolean generateFollowups, RunOptions options) {
    }
}
