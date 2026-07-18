package com.operativus.agentmanager.integration.teams;

import com.operativus.agentmanager.compute.teams.RouterOrchestrator;
import com.operativus.agentmanager.control.repository.AgentRepository;
import com.operativus.agentmanager.core.entity.AgentEntity;
import com.operativus.agentmanager.core.exception.BusinessValidationException;
import com.operativus.agentmanager.core.model.TenantConstants;
import com.operativus.agentmanager.core.model.AgentStreamEvent;
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
 *   {@link com.operativus.agentmanager.compute.teams.RouterOrchestrator} — the
 *   one-hop structured-routing team strategy. Unlike
 *   {@link com.operativus.agentmanager.compute.teams.CoordinatorOrchestrator}
 *   (which delegates through the root via tool-calling) the Router uses an
 *   internal {@link org.springframework.ai.chat.client.ChatClient} to pick ONE
 *   member via structured output and then delegates to THAT MEMBER directly.
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 *
 * Cases mapped to {@code docs/testing/agm-runtime-testing-spec.md} T027.
 *
 * Implementation notes / gaps these tests pin:
 *   - The Router's internal {@code ChatClient} is built from the Spring-provided
 *     {@code ChatClient.Builder} which in tests resolves to a builder over the
 *     {@link FakeChatModel} (see {@link FakeChatModelConfig}). Scripting the fake
 *     with a raw JSON {@code RouterDecision} drives the {@code .entity(RouterDecision.class)}
 *     structured-output path end to end through Spring AI's {@code BeanOutputConverter}.
 *   - On line 58 of {@code RouterOrchestrator.execute}, {@code generateFollowups} is
 *     HARD-CODED to {@code false} on the delegated run — the caller's value is
 *     IGNORED. Case 1 pins this contract; a future change that forwards the caller
 *     intent will flip the assertion.
 *   - {@code TransitionValidator} runs before delegation but returns early when a
 *     team has zero DAG edges ("unconstrained mode" line 40). Tests seed no
 *     {@code transition_edges} rows, so the validator is permissive.
 *   - {@code TierEscalationValidator} blocks TARGET_TIER &gt; SOURCE_TIER transitions.
 *     Tests leave all agents on the schema default ({@code security_tier = 1}) so
 *     same-tier transitions pass. A future case that flips tiers would pin the
 *     {@link com.operativus.agentmanager.core.exception.SwarmEscalationException}
 *     HITL path.
 *   - Empty-members guard uses {@link IllegalArgumentException} (line 65) while the
 *     all-inactive guard uses {@link BusinessValidationException} (line 76) — two
 *     different exception types for two similar invariants. Case 2 pins both types
 *     literally so a refactor that unifies them triggers a deliberate flip.
 *   - When the LLM returns an id NOT in the member set, the orchestrator throws a
 *     plain {@link RuntimeException} (line 111), NOT a domain exception. Case 2
 *     pins this shape — future hardening should promote it to a domain error.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class RouterOrchestratorRuntimeTest extends BaseIntegrationTest {

    @Autowired private RouterOrchestrator router;
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
     * T027 case 1 — happy path. A ROUTER team with three active members consults
     * the internal ChatClient, which (via scripted {@link FakeChatModel}) returns a
     * {@code RouterDecision} pointing at the SECOND member. The orchestrator must
     * delegate to THAT member — not the root, and not the first/default member —
     * and thread session/user/org/media through unchanged. {@code generateFollowups}
     * is overridden to {@code false} on the delegated run (line 58 of
     * {@link RouterOrchestrator}), even though the caller passed {@code true}.
     */
    @Test
    void execute_withActiveMembers_delegatesToLlmSelectedMember() {
        String memberA = persistAgent("member-a-" + UUID.randomUUID(), "Member A", true, false, null, null);
        String memberB = persistAgent("member-b-" + UUID.randomUUID(), "Member B", true, false, null, null);
        String memberC = persistAgent("member-c-" + UUID.randomUUID(), "Member C", true, false, null, null);
        String rootId = persistAgent("root-" + UUID.randomUUID(), "Router Root", true, false,
                "ROUTER", List.of(memberA, memberB, memberC));

        AgentDefinition rootDef = agentRegistry.findById(rootId, TenantConstants.DEFAULT_SYSTEM_ORG);
        assertNotNull(rootDef, "registry must resolve root agent after persist");

        fakeChatModel.respondWith("{\"targetAgentId\":\"" + memberB + "\",\"rationale\":\"Member B is best fit\"}");

        runner.nextResponse = new RunResponse("run-abc", "sess-1",
                "delegated output from member B", new HashMap<>(), new ArrayList<>(),
                new ArrayList<>(), RunStatus.COMPLETED, null);

        String output = router.execute(rootDef, "analyze inbound support ticket", null,
                "sess-1", "user-1", TenantConstants.DEFAULT_SYSTEM_ORG, Boolean.TRUE, runner);

        assertEquals("delegated output from member B", output,
                "orchestrator returns RunResponse.content() from the delegated member run");
        assertEquals(1, runner.calls.size(),
                "Router selects exactly one member per call — no fan-out");
        RecordedCall call = runner.calls.get(0);
        assertAll("delegated call targets the LLM-selected member and threads context params",
                () -> assertEquals(memberB, call.agentId,
                        "Router delegates to SELECTED MEMBER, not to root"),
                () -> assertEquals("analyze inbound support ticket", call.userInput),
                () -> assertEquals("sess-1", call.sessionId),
                () -> assertEquals("user-1", call.userId),
                () -> assertEquals(TenantConstants.DEFAULT_SYSTEM_ORG, call.orgId),
                () -> assertEquals(Boolean.FALSE, call.generateFollowups,
                        "generateFollowups is HARD-CODED to false on line 58 of RouterOrchestrator — caller's Boolean.TRUE is ignored"),
                () -> assertEquals(null, call.media),
                () -> assertEquals(null, call.options));
        assertEquals(1, fakeChatModel.receivedPrompts().size(),
                "Router must consult the LLM exactly once per execute() call");
    }

    /**
     * T027 case 2 — guards. The Router rejects three distinct invalid states, each
     * with its own exception type. All three paths are covered here via assertAll
     * so a single @Test pins the full matrix:
     *   (a) empty members list → {@link IllegalArgumentException} ("has no members defined")
     *   (b) members exist but all are inactive → registry returns nulls → filter strips
     *       them → {@link BusinessValidationException} ("No valid, active members")
     *   (c) LLM returns an id that is NOT in the member set → {@link RuntimeException}
     *       ("Router selected an invalid agent ID")
     */
    @Test
    void execute_invalidTeamOrDecision_throwsDistinctExceptionsPerPath() {
        assertAll("router guards: three distinct throw paths pinned by exception type",
                this::assertEmptyMembersThrowsIllegalArgument,
                this::assertAllInactiveThrowsBusinessValidation,
                this::assertLlmPicksUnknownIdThrowsRuntime);
    }

    private void assertEmptyMembersThrowsIllegalArgument() {
        runner = new RecordingAgentOperations();
        fakeChatModel.reset();

        String rootId = persistAgent("empty-members-" + UUID.randomUUID(), "Empty Router", true, false,
                "ROUTER", List.of());
        AgentDefinition rootDef = agentRegistry.findById(rootId, TenantConstants.DEFAULT_SYSTEM_ORG);
        assertNotNull(rootDef);

        // RouterOrchestrator unified the empty-members and all-inactive guards: an empty
        // member list now trips the same BusinessValidationException as the all-inactive
        // path ("No valid, active members found for routing." — RouterOrchestrator:115),
        // not the former IllegalArgumentException("has no members defined").
        BusinessValidationException ex = assertThrows(BusinessValidationException.class,
                () -> router.execute(rootDef, "anything", null, "sess-x", "user-x", TenantConstants.DEFAULT_SYSTEM_ORG,
                        Boolean.FALSE, runner));
        assertTrue(ex.getMessage().contains("No valid, active members found"),
                "empty members must throw BusinessValidationException 'No valid, active members found': " + ex.getMessage());
        assertEquals(0, runner.calls.size());
        assertEquals(0, fakeChatModel.receivedPrompts().size(),
                "LLM must not be consulted when member list is empty");
    }

    private void assertAllInactiveThrowsBusinessValidation() {
        runner = new RecordingAgentOperations();
        fakeChatModel.reset();

        String inactiveA = persistAgent("inactive-a-" + UUID.randomUUID(), "Inactive A", false, false, null, null);
        String inactiveB = persistAgent("inactive-b-" + UUID.randomUUID(), "Inactive B", false, false, null, null);
        String rootId = persistAgent("all-inactive-" + UUID.randomUUID(), "All-Inactive Router", true, false,
                "ROUTER", List.of(inactiveA, inactiveB));
        AgentDefinition rootDef = agentRegistry.findById(rootId, TenantConstants.DEFAULT_SYSTEM_ORG);
        assertNotNull(rootDef);

        BusinessValidationException ex = assertThrows(BusinessValidationException.class,
                () -> router.execute(rootDef, "anything", null, "sess-y", "user-y", TenantConstants.DEFAULT_SYSTEM_ORG,
                        Boolean.FALSE, runner));
        assertTrue(ex.getMessage().contains("No valid, active members"),
                "all-inactive members must trip the second guard as BusinessValidationException: " + ex.getMessage());
        assertEquals(0, runner.calls.size());
        assertEquals(0, fakeChatModel.receivedPrompts().size(),
                "LLM must not be consulted when no active members exist");
    }

    private void assertLlmPicksUnknownIdThrowsRuntime() {
        runner = new RecordingAgentOperations();
        fakeChatModel.reset();

        String memberA = persistAgent("member-real-" + UUID.randomUUID(), "Real Member", true, false, null, null);
        String rootId = persistAgent("root-bad-llm-" + UUID.randomUUID(), "Router Bad LLM", true, false,
                "ROUTER", List.of(memberA));
        AgentDefinition rootDef = agentRegistry.findById(rootId, TenantConstants.DEFAULT_SYSTEM_ORG);
        assertNotNull(rootDef);

        String ghostId = "ghost-agent-not-in-team";
        fakeChatModel.respondWith("{\"targetAgentId\":\"" + ghostId + "\",\"rationale\":\"fabricated\"}");

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> router.execute(rootDef, "anything", null, "sess-z", "user-z", TenantConstants.DEFAULT_SYSTEM_ORG,
                        Boolean.FALSE, runner));
        assertTrue(ex.getMessage().contains("Router selected an invalid agent ID"),
                "LLM-picked unknown id must throw RuntimeException with the invalid-id message: " + ex.getMessage());
        assertEquals(0, runner.calls.size(), "runner must not be invoked when LLM picks a ghost member");
    }

    private String persistAgent(String id, String name, boolean active, boolean maintenance,
                                String teamMode, List<String> members) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setOrgId(TenantConstants.DEFAULT_SYSTEM_ORG);
        a.setName(name);
        a.setDescription("T027 fixture: " + name);
        a.setInstructions("T027 fixture instructions");
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
        RunResponse nextResponse;

        @Override
        public RunResponse run(String agentId, String userInput, String sessionId) {
            throw new UnsupportedOperationException("minimal run overload not expected in T027");
        }

        @Override
        public RunResponse run(String agentId, String userInput, List<Media> media, String sessionId,
                                String userId, String orgId, Boolean generateFollowups, RunOptions options) {
            calls.add(new RecordedCall(agentId, userInput, media, sessionId, userId, orgId,
                    generateFollowups, options));
            return nextResponse != null ? nextResponse
                    : new RunResponse("run-" + seq.incrementAndGet(), sessionId, "", new HashMap<>(),
                            new ArrayList<>(), new ArrayList<>(), RunStatus.COMPLETED, null);
        }

        @Override
        public Flux<AgentStreamEvent> stream(String agentId, String userInput, List<Media> media,
                                              String sessionId, String userId, String orgId,
                                              Boolean generateFollowups, RunOptions options) {
            throw new UnsupportedOperationException("stream not expected in T027");
        }

        @Override
        public String runInBackground(String agentId, String userInput, List<Media> media,
                                       String sessionId, String userId, String orgId,
                                       Boolean generateFollowups, RunOptions options) {
            throw new UnsupportedOperationException("runInBackground not expected in T027");
        }

        @Override
        public String runInBackground(String agentId, String userInput, String sessionId) {
            throw new UnsupportedOperationException("runInBackground (minimal) not expected in T027");
        }

        @Override
        public String runPlayground(String agentId, String userInput, String sessionId) {
            throw new UnsupportedOperationException("runPlayground not expected in T027");
        }

        @Override
        public void cancelRun(String runId) {
            throw new UnsupportedOperationException("cancelRun not expected in T027");
        }

        @Override
        public RunResponse continueRun(String runId, String action) {
            throw new UnsupportedOperationException("continueRun not expected in T027");
        }

        @Override
        public void loadKnowledge(String agentId) {
            throw new UnsupportedOperationException("loadKnowledge not expected in T027");
        }
    }

    private record RecordedCall(String agentId, String userInput, List<Media> media, String sessionId,
                                 String userId, String orgId, Boolean generateFollowups, RunOptions options) {
    }
}
