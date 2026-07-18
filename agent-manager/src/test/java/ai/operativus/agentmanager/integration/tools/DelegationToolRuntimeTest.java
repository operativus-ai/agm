package ai.operativus.agentmanager.integration.tools;

import ai.operativus.agentmanager.compute.memory.EphemeralSwarmContext;
import ai.operativus.agentmanager.compute.tools.DelegationTool;
import ai.operativus.agentmanager.control.repository.AgentRepository;
import ai.operativus.agentmanager.control.repository.AgentRunEventRepository;
import ai.operativus.agentmanager.control.repository.TransitionEdgeRepository;
import ai.operativus.agentmanager.core.callback.AgentContextHolder;
import ai.operativus.agentmanager.core.entity.AgentEntity;
import ai.operativus.agentmanager.core.entity.AgentRunEventEntity;
import ai.operativus.agentmanager.core.entity.TransitionEdge;
import ai.operativus.agentmanager.core.event.AgentRunEventType;
import ai.operativus.agentmanager.core.model.AgentStreamEvent;
import ai.operativus.agentmanager.core.model.RunOptions;
import ai.operativus.agentmanager.core.model.RunResponse;
import ai.operativus.agentmanager.core.model.TenantConstants;
import ai.operativus.agentmanager.core.model.enums.RunStatus;
import ai.operativus.agentmanager.core.registry.AgentOperations;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import ai.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.test.context.TestConfiguration;
import reactor.core.publisher.Flux;

import java.time.Duration;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Domain Responsibility: Boot-context runtime coverage of
 *   {@link ai.operativus.agentmanager.compute.tools.DelegationTool#delegate_to_agent}
 *   — the synchronous delegation primitive that a Coordinator/Leader agent invokes
 *   as a Spring AI tool to dispatch a sub-task to a subordinate agent.
 *
 *   These tests pin behaviors that the unit-level tests
 *   ({@code DelegationToolTest}, {@code DelegationToolMemoryScopeTest},
 *   {@code DelegationToolNullProviderResolutionTest}) cannot prove because they
 *   stub the runner + validators. Here we boot the real Spring context with a
 *   pgvector Postgres (via {@link BaseIntegrationTest}), wire the real
 *   {@code TransitionValidator} + {@code TierEscalationValidator} +
 *   {@code AgentRunEventBus} + {@code EphemeralSwarmContext}, and substitute the
 *   {@link AgentOperations} runner with a recording double so we can assert exactly
 *   what the tool dispatched, while still exercising the production wire-up.
 *
 *   Cases covered:
 *     1. happy path — valid child agent: returns scripted content; runner invoked
 *        once with the correct arg vector (sessionId/userId/orgId threaded);
 *        DELEGATION_START + DELEGATION_COMPLETE events appear in
 *        {@code agent_run_events} keyed to the parent runId; ephemeral context is
 *        propagated when {@code workflowRunId} is bound.
 *     2. error path — invalid child agent id (target not in registry): returns the
 *        guard string {@code "Agent is unavailable or inactive: ..."}; START fires;
 *        COMPLETE fires with {@code status=error} + {@code errorClass}.
 *     3. tier escalation — child has higher securityTier than parent: returns the
 *        guard string {@code "Delegation blocked: Security tier escalation
 *        requires human approval. ..."}; COMPLETE event carries the
 *        {@code SwarmEscalationException} class.
 *     4. event-emission gate — when invoked OUTSIDE an agent run context
 *        ({@code currentRunId} ScopedValue unbound): tool still returns content
 *        but emits NO events (line 189-190 of DelegationTool — "delegation
 *        invoked outside an agent run — skip timeline event").
 *
 * State: Stateless. Per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}.
 *
 * <p>Implementation notes / gaps these tests pin:
 *   - The tool catches every checked + unchecked exception and shapes a string
 *     return — Spring AI tool semantics. So we never assertThrows; we assert on
 *     the returned guard-string AND on the COMPLETE event's payload to prove the
 *     thrown exception was observed by the tool.
 *   - {@code AgentRunEventBus.publish} fans out to a virtual-thread executor for
 *     persistence. Tests poll {@code agent_run_events} via Awaitility (default 5s
 *     timeout) before reading rows.
 *   - The DAG + tier validators receive {@code AgentContextHolder.getAgentId()}
 *     as the source-agent-id input (post-fix). {@code currentRunId} is a fresh
 *     UUID per run and is intentionally separate from the agent id; tests bind
 *     both ScopedValues independently to mirror how AgentService binds them in
 *     production (AgentService.java:301-305).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class,
        NoOpReflectionServiceConfig.class, DelegationToolRuntimeTest.RecordingRunnerConfig.class})
public class DelegationToolRuntimeTest extends BaseIntegrationTest {

    @Autowired private DelegationTool delegationTool;
    @Autowired private AgentRepository agentRepository;
    @Autowired private AgentRunEventRepository eventRepository;
    @Autowired private TransitionEdgeRepository edgeRepository;
    @Autowired private EphemeralSwarmContext ephemeralSwarmContext;
    @Autowired private RecordingAgentOperations runner;

    @BeforeEach
    void resetHarness() {
        runner.calls.clear();
        runner.scriptedResponses.clear();
        seedDefaultModel();
    }

    /**
     * Case 1 — happy path. A valid child agent is delegated to from within a bound
     * agent-run context with a workflowRunId. Asserts the full success vector:
     *   - returned content == scripted child response
     *   - runner invoked exactly once with all parent context propagated
     *   - DELEGATION_START + DELEGATION_COMPLETE rows persisted to agent_run_events
     *     keyed to parentRunId, both stamped with org/session
     *   - COMPLETE event payload carries status=ok, durationMs ≥ 0, contentLength
     *     equal to the scripted content's length
     *   - ephemeral context survived the child-session merge (mergeFrom path)
     */
    @Test
    void delegate_validChild_runsRunnerWithParentContext_andPersistsBothDelegationEvents() {
        String parentAgentId = persistAgent("parent-" + UUID.randomUUID(), "Parent Coordinator", 1);
        String childAgentId = persistAgent("child-" + UUID.randomUUID(), "Child Worker", 1);

        runner.scriptedResponses.put(childAgentId, "child-result-payload");

        String parentRunId = "run-parent-" + UUID.randomUUID();
        String parentSessionId = "sess-parent";
        String parentUserId = "user-parent";
        String workflowRunId = "wf-" + UUID.randomUUID();

        // Seed an ephemeral fact under the workflowRunId so we can verify mergeFrom
        // copied it onto the child session bucket (DelegationTool line 102-104).
        ephemeralSwarmContext.put(workflowRunId, "shared-fact", "alpha");

        String[] result = new String[1];
        runWithParentContext(parentRunId, parentSessionId, parentUserId, parentAgentId,
                workflowRunId, () -> result[0] = delegationTool.delegate_to_agent(childAgentId, "child task"));

        // Wait for async persistence of both delegation events.
        Awaitility.await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100))
                .until(() -> eventRepository.findByRunIdOrderByEventTsAsc(parentRunId).size() >= 2);

        List<AgentRunEventEntity> events = eventRepository.findByRunIdOrderByEventTsAsc(parentRunId);
        AgentRunEventEntity startEvent = events.stream()
                .filter(e -> e.getEventType() == AgentRunEventType.DELEGATION_START)
                .findFirst().orElseThrow(() -> new AssertionError("no DELEGATION_START persisted"));
        AgentRunEventEntity completeEvent = events.stream()
                .filter(e -> e.getEventType() == AgentRunEventType.DELEGATION_COMPLETE)
                .findFirst().orElseThrow(() -> new AssertionError("no DELEGATION_COMPLETE persisted"));

        Map<String, Object> startPayload = startEvent.getPayload();
        Map<String, Object> completePayload = completeEvent.getPayload();

        assertAll("delegation happy path",
                () -> assertEquals("child-result-payload", result[0],
                        "tool returns the child runner's scripted content verbatim"),
                () -> assertEquals(1, runner.calls.size(),
                        "child runner invoked exactly once"),
                () -> assertEquals(childAgentId, runner.calls.get(0).agentId(),
                        "runner targeted the requested child agent id"),
                () -> assertEquals("child task", runner.calls.get(0).userInput(),
                        "task argument propagated unchanged"),
                () -> assertEquals(parentUserId, runner.calls.get(0).userId(),
                        "parent userId threaded into child run"),
                () -> assertEquals(TenantConstants.DEFAULT_SYSTEM_ORG, runner.calls.get(0).orgId(),
                        "parent orgId threaded into child run"),
                () -> assertEquals(Boolean.FALSE, runner.calls.get(0).generateFollowups(),
                        "delegation HARD-CODES generateFollowups=false on the child run"),
                () -> assertEquals(parentRunId, startEvent.getRunId(),
                        "DELEGATION_START event keyed to parent runId"),
                () -> assertEquals(parentRunId, completeEvent.getRunId(),
                        "DELEGATION_COMPLETE event keyed to parent runId"),
                () -> assertEquals(TenantConstants.DEFAULT_SYSTEM_ORG, startEvent.getOrgId(),
                        "START event stamped with the bound orgId"),
                () -> assertEquals(parentSessionId, startEvent.getSessionId(),
                        "START event stamped with the bound sessionId"),
                () -> assertEquals(childAgentId, startPayload.get("targetAgentId"),
                        "START payload carries targetAgentId"),
                () -> assertEquals("child task".length(), startPayload.get("taskLength"),
                        "START payload carries taskLength"),
                () -> assertEquals(workflowRunId, startPayload.get("workflowRunId"),
                        "START payload carries workflowRunId when bound"),
                () -> assertEquals("ok", completePayload.get("status"),
                        "COMPLETE payload status=ok on success"),
                () -> assertEquals("child-result-payload".length(), completePayload.get("contentLength"),
                        "COMPLETE payload contentLength reflects returned content"),
                () -> assertNotNull(completePayload.get("durationMs"),
                        "COMPLETE payload includes durationMs"),
                () -> assertEquals("alpha",
                        ephemeralSwarmContext.get(runner.calls.get(0).sessionId(), "shared-fact", String.class),
                        "EphemeralSwarmContext.mergeFrom propagated parent's workflowRunId facts to child session bucket"));
    }

    /**
     * Case 2 — error path: the runner rejects the call with
     * ResourceNotFoundException (e.g. AgentService can't materialize the child
     * because the registry returns null mid-flight, or the agent was deleted
     * between tier-validation and dispatch). The child IS registered in the
     * agent table (so tier validator passes) but the runner stub is configured
     * to throw RNF for it. The tool catches the exception and shapes the
     * "Agent is unavailable or inactive:" guard string; COMPLETE event records
     * the exception class so observability can distinguish runner-thrown
     * RNF from validator-thrown BusinessValidationException.
     */
    @Test
    void delegate_runnerThrowsResourceNotFound_returnsGuardString_andCompleteEventCarriesErrorClass() {
        String parentAgentId = persistAgent("parent-err-" + UUID.randomUUID(), "Parent", 1);
        // Child IS registered (tier validator finds it) but runner rejects the dispatch.
        String childAgentId = persistAgent("child-err-" + UUID.randomUUID(), "Child", 1);
        runner.throwResourceNotFoundFor.add(childAgentId);

        String parentRunId = "run-err-" + UUID.randomUUID();
        String[] result = new String[1];
        runWithParentContext(parentRunId, "sess-err", "user-err", parentAgentId,
                null, () -> result[0] = delegationTool.delegate_to_agent(childAgentId, "task-x"));

        Awaitility.await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100))
                .until(() -> eventRepository.findByRunIdAndEventTypeOrderByEventTsAsc(
                        parentRunId, AgentRunEventType.DELEGATION_COMPLETE).size() == 1);

        AgentRunEventEntity completeEvent = eventRepository.findByRunIdAndEventTypeOrderByEventTsAsc(
                parentRunId, AgentRunEventType.DELEGATION_COMPLETE).get(0);
        Map<String, Object> payload = completeEvent.getPayload();

        assertAll("delegation invalid-id error shaping",
                () -> assertTrue(result[0].startsWith("Agent is unavailable or inactive:"),
                        "ResourceNotFoundException must be shaped to the 'Agent is unavailable or inactive:' guard string. Actual: " + result[0]),
                () -> assertEquals("error", payload.get("status"),
                        "COMPLETE payload status=error on caught exception"),
                () -> assertEquals("ResourceNotFoundException", payload.get("errorClass"),
                        "COMPLETE payload errorClass identifies the root exception"),
                () -> assertNotNull(payload.get("errorMessage"),
                        "COMPLETE payload includes errorMessage"),
                () -> assertEquals(childAgentId, payload.get("targetAgentId"),
                        "COMPLETE payload still carries the attempted targetAgentId"),
                () -> assertEquals(1,
                        eventRepository.findByRunIdAndEventTypeOrderByEventTsAsc(parentRunId, AgentRunEventType.DELEGATION_START).size(),
                        "DELEGATION_START fires unconditionally before the failed attempt"));
    }

    /**
     * Case 3 — tier escalation. Source agent is tier-1, target is tier-3. The
     * TierEscalationValidator throws SwarmEscalationException. The tool catches
     * it and shapes the dedicated 'Delegation blocked' guard string. COMPLETE
     * event records the escalation exception class.
     */
    @Test
    void delegate_tierEscalation_returnsBlockedGuardString_andCompleteEventCarriesEscalationClass() {
        String parentAgentId = persistAgent("parent-tier1-" + UUID.randomUUID(), "Tier 1 Parent", 1);
        String privilegedChildId = persistAgent("child-tier3-" + UUID.randomUUID(), "Tier 3 Privileged", 3);

        String parentRunId = "run-esc-" + UUID.randomUUID();
        String[] result = new String[1];
        runWithParentContext(parentRunId, "sess-esc", "user-esc", parentAgentId,
                null, () -> result[0] = delegationTool.delegate_to_agent(privilegedChildId, "needs-MFA-tier-work"));

        Awaitility.await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100))
                .until(() -> eventRepository.findByRunIdAndEventTypeOrderByEventTsAsc(
                        parentRunId, AgentRunEventType.DELEGATION_COMPLETE).size() == 1);

        AgentRunEventEntity completeEvent = eventRepository.findByRunIdAndEventTypeOrderByEventTsAsc(
                parentRunId, AgentRunEventType.DELEGATION_COMPLETE).get(0);
        Map<String, Object> payload = completeEvent.getPayload();

        assertAll("delegation tier-escalation shaping",
                () -> assertTrue(result[0].startsWith("Delegation blocked: Security tier escalation requires human approval."),
                        "SwarmEscalationException must be shaped to the dedicated 'Delegation blocked' guard string. Actual: " + result[0]),
                () -> assertEquals("error", payload.get("status"),
                        "COMPLETE payload status=error on tier escalation"),
                () -> assertEquals("SwarmEscalationException", payload.get("errorClass"),
                        "COMPLETE payload errorClass distinguishes escalation from generic ResourceNotFoundException"),
                () -> assertEquals(0, runner.calls.size(),
                        "child runner is NEVER invoked when tier validation rejects upfront"));
    }

    /**
     * Case 4 — event-emission gate. When the tool is invoked WITHOUT a bound
     * currentRunId ScopedValue, no parent run exists and the tool's
     * publishDelegationEvent() short-circuits at line 189-190. The tool still
     * runs the runner and returns content; only the timeline events are skipped.
     */
    @Test
    void delegate_outsideAgentRunContext_runsRunner_butSkipsEventEmission() {
        String childAgentId = persistAgent("child-noctx-" + UUID.randomUUID(), "Child", 1);
        runner.scriptedResponses.put(childAgentId, "no-context-result");

        // Snapshot row count BEFORE delegation so we can assert no rows were added.
        long eventRowsBefore = eventRepository.count();

        // No ScopedValue.where(...) wrapping → currentRunId unbound.
        String returned = delegationTool.delegate_to_agent(childAgentId, "task");

        // Brief grace period in case async persistence races. We assert NO rows of
        // either delegation event type were added (regardless of run id), since
        // any leak would manifest as a count delta.
        try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); fail("interrupted"); }

        long eventRowsAfter = eventRepository.count();
        long startCountAll = eventRepository.findAll().stream()
                .filter(e -> e.getEventType() == AgentRunEventType.DELEGATION_START).count();
        long completeCountAll = eventRepository.findAll().stream()
                .filter(e -> e.getEventType() == AgentRunEventType.DELEGATION_COMPLETE).count();

        assertAll("delegation outside agent-run context",
                () -> assertEquals("no-context-result", returned,
                        "runner is still invoked and content returned even when timeline is skipped"),
                () -> assertEquals(1, runner.calls.size(),
                        "runner called exactly once"),
                () -> assertEquals(eventRowsBefore, eventRowsAfter,
                        "NO event rows of any type were appended"),
                () -> assertEquals(0, startCountAll,
                        "no DELEGATION_START emitted (currentRunId unbound → publishDelegationEvent short-circuits)"),
                () -> assertEquals(0, completeCountAll,
                        "no DELEGATION_COMPLETE emitted"),
                () -> assertEquals(ai.operativus.agentmanager.core.model.SecurityPrincipals.SYSTEM_PRINCIPAL,
                        runner.calls.get(0).userId(),
                        "with no parent context bound, getUserId() falls back to SYSTEM_PRINCIPAL via SecurityContextHolder"),
                () -> assertNull(runner.calls.get(0).orgId(),
                        "with no parent context bound, getOrgId() returns null (no SecurityContextHolder fallback for orgId)"));
    }

    /**
     * Case 5 — teamRootId bound + matching DAG edge: DelegationTool's
     * TransitionValidator call resolves the bound teamRootId, finds the registered
     * edge for source→target, allows the transition, and the runner is dispatched.
     * Pins the wiring done by TeamOrchestrationEngine.executeSync (which binds
     * teamRootId = def.id()) so a future drop regresses loudly to no-op DAG
     * validation. Mirrors {@code OrchestratorValidatorsRuntimeTest} edge fixtures
     * but exercises them through the tool path instead of the validator directly.
     */
    @Test
    void delegate_teamRootIdBoundWithMatchingEdge_passesValidation_andDispatches() {
        String teamRootId = "team-root-" + UUID.randomUUID();
        seedTeam(teamRootId, "DAG Team — matching edge");
        String parentAgentId = persistAgent("parent-dag-" + UUID.randomUUID(), "Parent", 1);
        String childAgentId = persistAgent("child-dag-" + UUID.randomUUID(), "Child", 1);
        edgeRepository.save(new TransitionEdge(UUID.randomUUID().toString(),
                teamRootId, parentAgentId, childAgentId));
        runner.scriptedResponses.put(childAgentId, "edge-allowed-result");

        String parentRunId = "run-dag-ok-" + UUID.randomUUID();
        String[] result = new String[1];
        runWithTeamContext(parentRunId, "sess-dag-ok", "user-dag", parentAgentId, teamRootId,
                () -> result[0] = delegationTool.delegate_to_agent(childAgentId, "task"));

        assertAll("DAG-validated delegation success",
                () -> assertEquals("edge-allowed-result", result[0],
                        "registered edge → validator permits → runner dispatched"),
                () -> assertEquals(1, runner.calls.size(),
                        "child runner invoked exactly once"));
    }

    /**
     * Case 6 — teamRootId bound + NO edge for source→target: TransitionValidator
     * throws BusinessValidationException with "DAG constraint violation". The
     * tool catches it via the ResourceNotFoundException | BusinessValidationException
     * branch (line 96-101) and shapes the "Agent is unavailable or inactive:"
     * guard string. Runner is NEVER called; COMPLETE event records the
     * BusinessValidationException class, distinguishing DAG block from runner
     * failure or tier escalation.
     */
    @Test
    void delegate_teamRootIdBoundWithUnregisteredEdge_blocksWithDagViolation_andSkipsDispatch() {
        String teamRootId = "team-root-" + UUID.randomUUID();
        seedTeam(teamRootId, "DAG Team — unregistered edge");
        String parentAgentId = persistAgent("parent-dag-" + UUID.randomUUID(), "Parent", 1);
        String childAgentId = persistAgent("child-dag-" + UUID.randomUUID(), "Child", 1);
        String otherAgentId = persistAgent("other-dag-" + UUID.randomUUID(), "Other", 1);
        // Register parent→other edge, but caller asks for parent→child → not in set.
        edgeRepository.save(new TransitionEdge(UUID.randomUUID().toString(),
                teamRootId, parentAgentId, otherAgentId));

        String parentRunId = "run-dag-blocked-" + UUID.randomUUID();
        String[] result = new String[1];
        runWithTeamContext(parentRunId, "sess-dag-blocked", "user-dag", parentAgentId, teamRootId,
                () -> result[0] = delegationTool.delegate_to_agent(childAgentId, "task"));

        Awaitility.await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100))
                .until(() -> eventRepository.findByRunIdAndEventTypeOrderByEventTsAsc(
                        parentRunId, AgentRunEventType.DELEGATION_COMPLETE).size() == 1);

        AgentRunEventEntity completeEvent = eventRepository.findByRunIdAndEventTypeOrderByEventTsAsc(
                parentRunId, AgentRunEventType.DELEGATION_COMPLETE).get(0);
        Map<String, Object> payload = completeEvent.getPayload();

        assertAll("DAG block shaped to guard string",
                () -> assertTrue(result[0].startsWith("Agent is unavailable or inactive:"),
                        "BusinessValidationException must be shaped to guard string. Actual: " + result[0]),
                () -> assertTrue(result[0].contains("DAG constraint violation"),
                        "guard string must surface the DAG-violation message. Actual: " + result[0]),
                () -> assertEquals(0, runner.calls.size(),
                        "runner is NEVER invoked when DAG validation rejects"),
                () -> assertEquals("error", payload.get("status"),
                        "COMPLETE payload status=error on DAG rejection"),
                () -> assertEquals("BusinessValidationException", payload.get("errorClass"),
                        "errorClass distinguishes DAG block from tier escalation or RNF"));
    }

    private void seedTeam(String teamId, String name) {
        jdbc.update("""
                INSERT INTO teams (id, name, description, created_at, updated_at)
                VALUES (?, ?, 'DelegationToolRuntimeTest fixture', now(), now())
                ON CONFLICT (id) DO NOTHING
                """, teamId, name);
    }

    /**
     * Bind every ScopedValue the DelegationTool reads, then run body. Mirrors how
     * AgentService binds context before invoking the agent's chat client + tools.
     */
    private void runWithParentContext(String parentRunId, String sessionId, String userId,
                                      String agentId, String workflowRunId, Runnable body) {
        ScopedValue.Carrier carrier = ScopedValue
                .where(AgentContextHolder.currentRunId, parentRunId)
                .where(AgentContextHolder.sessionId, sessionId)
                .where(AgentContextHolder.userId, userId)
                .where(AgentContextHolder.orgId, TenantConstants.DEFAULT_SYSTEM_ORG)
                .where(AgentContextHolder.agentId, agentId)
                .where(AgentContextHolder.orchestrationDepth, 1);
        if (workflowRunId != null) {
            carrier = carrier.where(AgentContextHolder.workflowRunId, new String[]{workflowRunId});
        }
        carrier.run(body);
    }

    /**
     * Variant of {@link #runWithParentContext} that ALSO binds teamRootId — the
     * ScopedValue that {@link ai.operativus.agentmanager.compute.service.TeamOrchestrationEngine}
     * binds to the team's root agent id at executeSync/executeStream entry. Used
     * by the DAG-validation cases to exercise the full path through
     * {@link ai.operativus.agentmanager.compute.tools.DelegationTool} into
     * {@link ai.operativus.agentmanager.compute.teams.TransitionValidator}.
     */
    private void runWithTeamContext(String parentRunId, String sessionId, String userId,
                                    String agentId, String teamRootId, Runnable body) {
        ScopedValue
                .where(AgentContextHolder.currentRunId, parentRunId)
                .where(AgentContextHolder.sessionId, sessionId)
                .where(AgentContextHolder.userId, userId)
                .where(AgentContextHolder.orgId, TenantConstants.DEFAULT_SYSTEM_ORG)
                .where(AgentContextHolder.agentId, agentId)
                .where(AgentContextHolder.teamRootId, teamRootId)
                .where(AgentContextHolder.orchestrationDepth, 1)
                .run(body);
    }

    private String persistAgent(String id, String name, int securityTier) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setOrgId(TenantConstants.DEFAULT_SYSTEM_ORG);
        a.setName(name);
        a.setDescription("delegation-runtime fixture: " + name);
        a.setInstructions("delegation-runtime fixture instructions");
        a.setModelId("gpt-4o-mini");
        a.setActive(true);
        a.setMaintenanceMode(false);
        a.setSecurityTier(securityTier);
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
     * Test-only @Primary substitution of the {@link AgentOperations} bean. The
     * production runner is {@code AgentService}, which would launch the full
     * advisor chain + LLM call for the child run. This recorder lets the test
     * pin DelegationTool's contract without entangling the much larger surface
     * of AgentService (covered by other suites).
     */
    @TestConfiguration
    @Profile("test")
    static class RecordingRunnerConfig {
        @Bean
        @Primary
        RecordingAgentOperations recordingAgentOperations() {
            return new RecordingAgentOperations();
        }
    }

    static final class RecordingAgentOperations implements AgentOperations {
        private final AtomicInteger seq = new AtomicInteger();
        final Map<String, String> scriptedResponses = new ConcurrentHashMap<>();
        final java.util.Set<String> throwResourceNotFoundFor = java.util.concurrent.ConcurrentHashMap.newKeySet();
        final List<RecordedCall> calls = new CopyOnWriteArrayList<>();

        @Override
        public RunResponse run(String agentId, String userInput, String sessionId) {
            throw new UnsupportedOperationException("minimal run overload not expected in delegation runtime tests");
        }

        @Override
        public RunResponse run(String agentId, String userInput, List<Media> media, String sessionId,
                               String userId, String orgId, Boolean generateFollowups, RunOptions options) {
            calls.add(new RecordedCall(agentId, userInput, media, sessionId, userId, orgId,
                    generateFollowups, options));
            if (throwResourceNotFoundFor.contains(agentId)) {
                throw new ai.operativus.agentmanager.core.exception.ResourceNotFoundException(
                        "Agent", agentId);
            }
            String content = scriptedResponses.getOrDefault(agentId, "default-content-for-" + agentId);
            return new RunResponse("run-" + seq.incrementAndGet(), sessionId, content,
                    new HashMap<>(), new ArrayList<>(), new ArrayList<>(), RunStatus.COMPLETED, null);
        }

        @Override
        public Flux<AgentStreamEvent> stream(String agentId, String userInput, List<Media> media,
                                             String sessionId, String userId, String orgId,
                                             Boolean generateFollowups, RunOptions options) {
            throw new UnsupportedOperationException("stream not expected in delegation runtime tests");
        }

        @Override
        public String runInBackground(String agentId, String userInput, List<Media> media,
                                      String sessionId, String userId, String orgId,
                                      Boolean generateFollowups, RunOptions options) {
            throw new UnsupportedOperationException("runInBackground not expected in delegation runtime tests");
        }

        @Override
        public String runInBackground(String agentId, String userInput, String sessionId) {
            throw new UnsupportedOperationException("runInBackground (minimal) not expected in delegation runtime tests");
        }

        @Override
        public String runPlayground(String agentId, String userInput, String sessionId) {
            throw new UnsupportedOperationException("runPlayground not expected in delegation runtime tests");
        }

        @Override
        public void cancelRun(String runId) {
            throw new UnsupportedOperationException("cancelRun not expected in delegation runtime tests");
        }

        @Override
        public RunResponse continueRun(String runId, String action) {
            throw new UnsupportedOperationException("continueRun not expected in delegation runtime tests");
        }

        @Override
        public void loadKnowledge(String agentId) {
            throw new UnsupportedOperationException("loadKnowledge not expected in delegation runtime tests");
        }
    }

    private record RecordedCall(String agentId, String userInput, List<Media> media, String sessionId,
                                String userId, String orgId, Boolean generateFollowups, RunOptions options) {
    }
}
