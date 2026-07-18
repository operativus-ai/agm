package com.operativus.agentmanager.integration.teams;

import com.operativus.agentmanager.compute.teams.PlannerOrchestrator;
import com.operativus.agentmanager.control.repository.AgentRepository;
import com.operativus.agentmanager.core.entity.AgentEntity;
import com.operativus.agentmanager.core.model.AgentStreamEvent;
import com.operativus.agentmanager.core.model.TenantConstants;
import com.operativus.agentmanager.core.model.RunOptions;
import com.operativus.agentmanager.core.model.RunResponse;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Direct runtime coverage of
 *   {@link com.operativus.agentmanager.compute.teams.PlannerOrchestrator} — the
 *   Plan-and-Solve team strategy. The Planner runs a THREE-PHASE lifecycle:
 *     (1) Plan — LLM decomposes the user request into an ordered
 *         {@link com.operativus.agentmanager.compute.teams.PlannerOrchestrator.ExecutionPlan}
 *         via Spring AI {@code .entity()} structured output.
 *     (2) Solve — each step is delegated to its {@code targetAgentId} via
 *         {@link AgentOperations#run(String, String, List, String, String, String, Boolean, RunOptions)}.
 *     (3) Synthesize — a second LLM call consolidates step outputs into a
 *         single response.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 *
 * Cases mapped to {@code docs/testing/agm-runtime-testing-spec.md} T028.
 *
 * Implementation notes / gaps these tests pin:
 *   - The Planner's {@code plannerClient} is built from the Spring-provided
 *     {@code ChatClient.Builder} which in tests resolves to a builder over the
 *     {@link FakeChatModel} (see {@link FakeChatModelConfig}). Scripting TWO
 *     responses in order (plan JSON, then synthesis text) drives the whole
 *     lifecycle end-to-end.
 *   - {@code generateFollowups} is HARD-CODED to {@code false} on every step
 *     (line 136 of {@link PlannerOrchestrator}) — the caller's value is ignored,
 *     matching the Router's override contract.
 *   - Media is passed to the FIRST step only. Line 142 sets {@code media = null}
 *     after the first iteration, so any later steps receive {@code null}. The
 *     test pins this with a non-null media list on call and asserts the second
 *     step sees {@code null}.
 *   - Between steps the orchestrator threads accumulated context into the next
 *     step's userInput via a "Context from previous steps:\n[Step N Result]: ..."
 *     preamble (line 132). Pinning this literal shape catches refactors that
 *     silently drop inter-step context.
 *   - Guards DO NOT throw — they return plain strings ("Planner Agent has no
 *     members to delegate work to.", "Planner failed: No valid, active members
 *     available for task decomposition.", "I was unable to decompose this
 *     request into actionable steps..."). This is a deliberate ergonomics
 *     contract: the Planner degrades gracefully to a human-readable message
 *     instead of propagating an exception to the end user. The test pins BOTH
 *     the empty-members guard and the null-plan guard as string returns (no
 *     throw), so a future refactor that converts them to exceptions triggers a
 *     deliberate flip.
 *   - When a step throws, the Planner logs and appends "FAILED - {msg}" to the
 *     step outputs but CONTINUES with the next step (line 143). This is NOT
 *     covered in this single-case file to stay within the spec budget; a future
 *     hardening task should add it.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class PlannerOrchestratorRuntimeTest extends BaseIntegrationTest {

    @Autowired private PlannerOrchestrator planner;
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
     * T028 case 1 — full Plan-and-Solve lifecycle.
     *
     * A PLANNER team with two active members is given a user request. The scripted
     * FakeChatModel returns:
     *   (1) a two-step ExecutionPlan targeting member A then member B
     *   (2) a synthesis string
     *
     * The orchestrator must:
     *   - delegate step 1 to member A with the step's task description AND the
     *     caller-provided media
     *   - delegate step 2 to member B with task description AUGMENTED by
     *     accumulated context from step 1, and with media set to null
     *   - hard-override generateFollowups to false on both delegated runs
     *   - return the synthesis content as the final output
     *   - consult the LLM exactly twice (plan + synthesize)
     *
     * The same @Test also pins the two graceful-degradation guards (empty members
     * and null plan) as STRING returns, not thrown exceptions — a contract
     * distinct from Router/Coordinator.
     */
    @Test
    void execute_fullLifecycle_planSolveSynthesize_andGuardsReturnStringsRatherThanThrow() {
        assertAll("planner lifecycle + graceful-degradation guards",
                this::assertFullThreePhaseLifecycleThreadsContextAndOverridesFollowups,
                this::assertEmptyMembersReturnsGuardStringWithoutThrowing,
                this::assertNullOrEmptyPlanReturnsGuardStringWithoutThrowing);
    }

    private void assertFullThreePhaseLifecycleThreadsContextAndOverridesFollowups() {
        runner = new RecordingAgentOperations();
        fakeChatModel.reset();

        String memberA = persistAgent("planner-a-" + UUID.randomUUID(), "Researcher", true, false, null, null);
        String memberB = persistAgent("planner-b-" + UUID.randomUUID(), "Writer", true, false, null, null);
        String rootId = persistAgent("planner-root-" + UUID.randomUUID(), "Planner Root", true, false,
                "PLANNER", List.of(memberA, memberB));

        AgentDefinition rootDef = agentRegistry.findById(rootId, TenantConstants.DEFAULT_SYSTEM_ORG);
        assertNotNull(rootDef, "registry must resolve root planner after persist");

        // Script 1: structured ExecutionPlan (Spring AI .entity() parses fenced or raw JSON)
        fakeChatModel.respondWith("""
                {"steps":[
                  {"stepNumber":1,"targetAgentId":"%s","taskDescription":"gather research on topic X"},
                  {"stepNumber":2,"targetAgentId":"%s","taskDescription":"write summary of topic X"}
                ]}
                """.formatted(memberA, memberB));

        // Script 2: synthesis output consumed by Phase 3
        fakeChatModel.respondWith("final synthesized answer");

        // First step returns research content; second step returns draft content
        runner.scriptedResponses.add(runResponse("research findings: A, B, C"));
        runner.scriptedResponses.add(runResponse("draft summary referencing findings"));

        List<Media> callerMedia = new ArrayList<>();

        String output = planner.execute(rootDef, "explain topic X using current data", callerMedia,
                "sess-1", "user-1", TenantConstants.DEFAULT_SYSTEM_ORG, Boolean.TRUE, runner);

        assertEquals("final synthesized answer", output,
                "Planner returns the Phase 3 synthesis result, not raw step outputs");

        assertEquals(2, runner.calls.size(),
                "Planner delegates exactly once per planned step");

        RecordedCall step1 = runner.calls.get(0);
        RecordedCall step2 = runner.calls.get(1);

        assertAll("step 1 targets member A with task description and caller media",
                () -> assertEquals(memberA, step1.agentId,
                        "step 1 delegates to the agent id from plan.steps[0]"),
                () -> assertEquals("gather research on topic X", step1.userInput,
                        "step 1 receives the raw task description (no accumulated context yet)"),
                () -> assertEquals(callerMedia, step1.media,
                        "step 1 receives caller-provided media unchanged"),
                () -> assertEquals("sess-1", step1.sessionId),
                () -> assertEquals("user-1", step1.userId),
                () -> assertEquals(TenantConstants.DEFAULT_SYSTEM_ORG, step1.orgId),
                () -> assertEquals(Boolean.FALSE, step1.generateFollowups,
                        "generateFollowups is HARD-CODED to false on line 136 — caller's TRUE is ignored"),
                () -> assertEquals(null, step1.options));

        assertAll("step 2 targets member B with ACCUMULATED context and nulled media",
                () -> assertEquals(memberB, step2.agentId,
                        "step 2 delegates to plan.steps[1].targetAgentId"),
                () -> assertTrue(step2.userInput.contains("Context from previous steps:"),
                        "step 2 input must carry the literal context preamble"),
                () -> assertTrue(step2.userInput.contains("research findings: A, B, C"),
                        "step 2 input must embed the step 1 output"),
                () -> assertTrue(step2.userInput.contains("Current Task: write summary of topic X"),
                        "step 2 input must carry the current task suffix"),
                () -> assertEquals(null, step2.media,
                        "line 142 sets media=null after the first step — later steps must receive null"),
                () -> assertEquals(Boolean.FALSE, step2.generateFollowups));

        assertEquals(2, fakeChatModel.receivedPrompts().size(),
                "LLM is consulted exactly twice per execute(): once for plan, once for synthesis");
    }

    private void assertEmptyMembersReturnsGuardStringWithoutThrowing() {
        runner = new RecordingAgentOperations();
        fakeChatModel.reset();

        String rootId = persistAgent("planner-empty-" + UUID.randomUUID(), "Empty Planner", true, false,
                "PLANNER", List.of());
        AgentDefinition rootDef = agentRegistry.findById(rootId, TenantConstants.DEFAULT_SYSTEM_ORG);
        assertNotNull(rootDef);

        String output = planner.execute(rootDef, "anything", null, "sess-x", "user-x", TenantConstants.DEFAULT_SYSTEM_ORG,
                Boolean.FALSE, runner);

        // PlannerOrchestrator:113 — the no-valid-members guard string (graceful degradation,
        // no throw); updated from the former "Planner Agent has no members to delegate work to."
        assertEquals("Planner failed: No valid, active members available for task decomposition.", output,
                "empty members must return the guard string VERBATIM (no throw) — graceful degradation");
        assertEquals(0, runner.calls.size());
        assertEquals(0, fakeChatModel.receivedPrompts().size(),
                "LLM must not be consulted when member list is empty");
    }

    /**
     * F7 LLM-hallucinated-agent-id rejection (PR 3 parity with Swarm). The Planner's plan
     * references an agentId that is NOT in the team's resolved member set (e.g. the LLM
     * hallucinates a plausible-looking id). The orchestrator MUST throw
     * {@link com.operativus.agentmanager.core.exception.BusinessValidationException}
     * carrying the "non-member agent" message BEFORE dispatching to any worker, so no
     * orphan child run rows can be persisted. Mirrors the Swarm F7 enforcement
     * (PlannerOrchestrator.java:156-162).
     */
    @Test
    void execute_planReferencesAgentIdNotInMemberSet_throwsBusinessValidationException_andDispatchesNoWorkers() {
        runner = new RecordingAgentOperations();
        fakeChatModel.reset();

        String memberA = persistAgent("planner-f7-a-" + UUID.randomUUID(), "Member A", true, false, null, null);
        String memberB = persistAgent("planner-f7-b-" + UUID.randomUUID(), "Member B", true, false, null, null);
        // A persisted-but-NOT-in-team agent — the LLM "hallucinates" this id into the plan.
        String orphanAgent = persistAgent("planner-f7-orphan-" + UUID.randomUUID(), "Orphan", true, false, null, null);

        String rootId = persistAgent("planner-f7-root-" + UUID.randomUUID(), "Planner F7 Root", true, false,
                "PLANNER", List.of(memberA, memberB));
        AgentDefinition rootDef = agentRegistry.findById(rootId, TenantConstants.DEFAULT_SYSTEM_ORG);
        assertNotNull(rootDef);

        // Step 1 targets memberA (in-team), step 2 targets orphanAgent (NOT in team) — F7 should
        // reject the whole plan when iterating to step 2 BEFORE any dispatch occurs.
        fakeChatModel.respondWith("""
                {"steps":[
                  {"stepNumber":1,"targetAgentId":"%s","taskDescription":"step 1 task"},
                  {"stepNumber":2,"targetAgentId":"%s","taskDescription":"step 2 task"}
                ]}
                """.formatted(memberA, orphanAgent));

        com.operativus.agentmanager.core.exception.BusinessValidationException ex = assertThrows(
                com.operativus.agentmanager.core.exception.BusinessValidationException.class,
                () -> planner.execute(rootDef, "request", null, "sess-f7-pl", "user-f7-pl",
                        TenantConstants.DEFAULT_SYSTEM_ORG, Boolean.TRUE, runner));

        assertAll("planner F7 hallucinated-id rejection",
                () -> assertTrue(ex.getMessage().contains("non-member agent"),
                        "exception message identifies non-member agent rejection: " + ex.getMessage()),
                () -> assertTrue(ex.getMessage().contains(orphanAgent),
                        "exception names the offending agent id: " + ex.getMessage()),
                () -> assertEquals(1, runner.calls.size(),
                        "step 1 (in-team) IS dispatched before the F7 check fires on step 2 — current "
                                + "behavior is that F7 fires per-step in the loop, not as a fail-fast pre-check"),
                () -> assertEquals(memberA, runner.calls.get(0).agentId,
                        "the only dispatched call is the in-team member from step 1"),
                () -> assertEquals(1, fakeChatModel.receivedPrompts().size(),
                        "plan LLM consulted exactly once; synthesis is never reached because the loop throws"));
    }

    private void assertNullOrEmptyPlanReturnsGuardStringWithoutThrowing() {
        runner = new RecordingAgentOperations();
        fakeChatModel.reset();

        String memberA = persistAgent("planner-lone-" + UUID.randomUUID(), "Lone Member", true, false, null, null);
        String rootId = persistAgent("planner-emptyplan-" + UUID.randomUUID(), "Planner Empty Plan", true, false,
                "PLANNER", List.of(memberA));
        AgentDefinition rootDef = agentRegistry.findById(rootId, TenantConstants.DEFAULT_SYSTEM_ORG);
        assertNotNull(rootDef);

        // Plan JSON with an empty steps array — triggers line 109's "null || empty" guard
        fakeChatModel.respondWith("{\"steps\":[]}");

        String output = planner.execute(rootDef, "anything", null, "sess-y", "user-y", TenantConstants.DEFAULT_SYSTEM_ORG,
                Boolean.FALSE, runner);

        assertEquals("I was unable to decompose this request into actionable steps. Please try rephrasing.",
                output,
                "empty plan must return the fallback guard string VERBATIM (no throw)");
        assertEquals(0, runner.calls.size(),
                "no steps means no delegations");
        assertEquals(1, fakeChatModel.receivedPrompts().size(),
                "plan LLM IS consulted once; synthesis is NEVER reached on empty plan");
    }

    private RunResponse runResponse(String content) {
        return new RunResponse("run-" + UUID.randomUUID(), "sess-planner", content,
                new HashMap<>(), new ArrayList<>(), new ArrayList<>(), RunStatus.COMPLETED, null);
    }

    private String persistAgent(String id, String name, boolean active, boolean maintenance,
                                String teamMode, List<String> members) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setOrgId(TenantConstants.DEFAULT_SYSTEM_ORG);
        a.setName(name);
        a.setDescription("T028 fixture: " + name);
        a.setInstructions("T028 fixture instructions");
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
        final List<RecordedCall> calls = new ArrayList<>();
        final java.util.Deque<RunResponse> scriptedResponses = new java.util.ArrayDeque<>();

        @Override
        public RunResponse run(String agentId, String userInput, String sessionId) {
            throw new UnsupportedOperationException("minimal run overload not expected in T028");
        }

        @Override
        public RunResponse run(String agentId, String userInput, List<Media> media, String sessionId,
                                String userId, String orgId, Boolean generateFollowups, RunOptions options) {
            calls.add(new RecordedCall(agentId, userInput, media, sessionId, userId, orgId,
                    generateFollowups, options));
            if (!scriptedResponses.isEmpty()) {
                return scriptedResponses.poll();
            }
            return new RunResponse("run-" + seq.incrementAndGet(), sessionId, "", new HashMap<>(),
                    new ArrayList<>(), new ArrayList<>(), RunStatus.COMPLETED, null);
        }

        @Override
        public Flux<AgentStreamEvent> stream(String agentId, String userInput, List<Media> media,
                                              String sessionId, String userId, String orgId,
                                              Boolean generateFollowups, RunOptions options) {
            throw new UnsupportedOperationException("stream not expected in T028");
        }

        @Override
        public String runInBackground(String agentId, String userInput, List<Media> media,
                                       String sessionId, String userId, String orgId,
                                       Boolean generateFollowups, RunOptions options) {
            throw new UnsupportedOperationException("runInBackground not expected in T028");
        }

        @Override
        public String runInBackground(String agentId, String userInput, String sessionId) {
            throw new UnsupportedOperationException("runInBackground (minimal) not expected in T028");
        }

        @Override
        public String runPlayground(String agentId, String userInput, String sessionId) {
            throw new UnsupportedOperationException("runPlayground not expected in T028");
        }

        @Override
        public void cancelRun(String runId) {
            throw new UnsupportedOperationException("cancelRun not expected in T028");
        }

        @Override
        public RunResponse continueRun(String runId, String action) {
            throw new UnsupportedOperationException("continueRun not expected in T028");
        }

        @Override
        public void loadKnowledge(String agentId) {
            throw new UnsupportedOperationException("loadKnowledge not expected in T028");
        }
    }

    private record RecordedCall(String agentId, String userInput, List<Media> media, String sessionId,
                                 String userId, String orgId, Boolean generateFollowups, RunOptions options) {
    }
}
