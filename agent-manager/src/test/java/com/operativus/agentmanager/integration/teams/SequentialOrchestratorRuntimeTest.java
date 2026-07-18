package com.operativus.agentmanager.integration.teams;

import com.operativus.agentmanager.compute.teams.SequentialOrchestrator;
import com.operativus.agentmanager.control.repository.AgentRepository;
import com.operativus.agentmanager.core.entity.AgentEntity;
import com.operativus.agentmanager.core.model.AgentStreamEvent;
import com.operativus.agentmanager.core.model.RunOptions;
import com.operativus.agentmanager.core.model.RunResponse;
import com.operativus.agentmanager.core.model.definitions.AgentDefinition;
import com.operativus.agentmanager.core.model.TenantConstants;
import com.operativus.agentmanager.core.model.definitions.AgentRegistry;
import com.operativus.agentmanager.core.model.enums.RunStatus;
import com.operativus.agentmanager.core.registry.AgentOperations;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
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

/**
 * Domain Responsibility: Direct runtime coverage of
 *   {@link com.operativus.agentmanager.compute.teams.SequentialOrchestrator} — the
 *   chain strategy. Each member's {@code RunResponse.content()} becomes the NEXT
 *   member's user input, in member-order as declared on the root agent.
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 *
 * Cases mapped to {@code docs/testing/agm-runtime-testing-spec.md} T029.
 *
 * Implementation notes:
 *   - Sequential filters members by {@code active} / {@code maintenanceMode}
 *     and drops the self id via {@link com.operativus.agentmanager.compute.teams
 *     .OrchestratorMembers#resolveActive} before iterating — matches the
 *     predicate used by Router, Coordinator, and Swarm.
 *   - {@code generateFollowups} is HARD-CODED to {@code false} on every step
 *     (line 47 of {@link SequentialOrchestrator}), matching the contract used by
 *     Router/Planner/Swarm/Debate/ActorCritic/Broadcast/Coordinator. Followup
 *     generation for the team's final output is the outer caller's responsibility.
 *   - Media is passed to the FIRST step only; line 46 sets {@code media = null}
 *     after the first iteration.
 *   - Empty members returns the string "Team has no members." (no throw).
 *   - There is NO final-synthesis phase — the return value is the LAST member's
 *     content, NOT an aggregated summary. Pinned by asserting the return equals
 *     the last runner response exactly.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class SequentialOrchestratorRuntimeTest extends BaseIntegrationTest {

    @Autowired private SequentialOrchestrator sequential;
    @Autowired private AgentRegistry agentRegistry;
    @Autowired private AgentRepository agentRepository;

    private RecordingAgentOperations runner;

    @BeforeEach
    void resetHarness() {
        runner = new RecordingAgentOperations();
        seedDefaultModel();
    }

    /**
     * T029 case 1 — chain execution + guard.
     *
     * Comprehensive single @Test via assertAll covering:
     *   (a) three-member chain: each member is invoked in declaration order;
     *       each step's input equals the prior step's output; media is passed
     *       only to the first member; generateFollowups is suppressed to
     *       {@code false} on every step regardless of caller's input; return
     *       value equals the LAST member's content (not an aggregation).
     *   (b) empty-members guard: returns the literal "Team has no members."
     *       string without throwing and without invoking the runner.
     *   (c) inactive-member filter: Sequential resolves members via
     *       {@code OrchestratorMembers.resolveActive} — an inactive id in the
     *       members list is dropped before iteration; the runner is NOT invoked
     *       for it.
     */
    @Test
    void execute_chainsMembersInOrderAndFiltersInactiveMembers() {
        assertAll("sequential chain + guards + active-filter",
                this::assertChainFeedsOutputsForwardAndThreadsGenerateFollowups,
                this::assertEmptyMembersReturnsGuardStringWithoutThrowing,
                this::assertInactiveMemberIsFilteredOut);
    }

    private void assertChainFeedsOutputsForwardAndThreadsGenerateFollowups() {
        runner = new RecordingAgentOperations();

        String m1 = persistAgent("seq-m1-" + UUID.randomUUID(), "Stage 1", true, false, null, null);
        String m2 = persistAgent("seq-m2-" + UUID.randomUUID(), "Stage 2", true, false, null, null);
        String m3 = persistAgent("seq-m3-" + UUID.randomUUID(), "Stage 3", true, false, null, null);
        String rootId = persistAgent("seq-root-" + UUID.randomUUID(), "Sequential Root", true, false,
                "SEQUENTIAL", List.of(m1, m2, m3));

        AgentDefinition rootDef = agentRegistry.findById(rootId, TenantConstants.DEFAULT_SYSTEM_ORG);
        assertNotNull(rootDef);

        runner.scriptedResponses.add(runResponse("stage 1 output"));
        runner.scriptedResponses.add(runResponse("stage 2 output"));
        runner.scriptedResponses.add(runResponse("stage 3 final output"));

        List<Media> callerMedia = new ArrayList<>();

        String output = sequential.execute(rootDef, "initial prompt", callerMedia,
                "sess-1", "user-1", TenantConstants.DEFAULT_SYSTEM_ORG, Boolean.TRUE, runner);

        assertEquals("stage 3 final output", output,
                "Sequential returns the LAST member's content — no aggregate synthesis");
        assertEquals(3, runner.calls.size(),
                "runner invoked exactly once per declared member");

        RecordedCall c1 = runner.calls.get(0);
        RecordedCall c2 = runner.calls.get(1);
        RecordedCall c3 = runner.calls.get(2);

        assertAll("step 1: first member, raw initial input, caller media",
                () -> assertEquals(m1, c1.agentId),
                () -> assertEquals("initial prompt", c1.userInput),
                () -> assertEquals(callerMedia, c1.media,
                        "first step receives caller-provided media unchanged"),
                () -> assertEquals("sess-1", c1.sessionId),
                () -> assertEquals("user-1", c1.userId),
                () -> assertEquals(TenantConstants.DEFAULT_SYSTEM_ORG, c1.orgId),
                () -> assertEquals(Boolean.FALSE, c1.generateFollowups,
                        "Sequential suppresses generateFollowups to false on every step regardless of caller's input — caller passed TRUE here"),
                () -> assertEquals(null, c1.options));

        assertAll("step 2: stage-1 output forwarded as input; media NULLED",
                () -> assertEquals(m2, c2.agentId),
                () -> assertEquals("stage 1 output", c2.userInput,
                        "step 2 input MUST equal step 1's content — chain contract"),
                () -> assertEquals(null, c2.media,
                        "line 46 sets media=null after first iteration — subsequent steps receive null"),
                () -> assertEquals(Boolean.FALSE, c2.generateFollowups));

        assertAll("step 3: stage-2 output forwarded as input; media still null",
                () -> assertEquals(m3, c3.agentId),
                () -> assertEquals("stage 2 output", c3.userInput,
                        "step 3 input MUST equal step 2's content — chain contract"),
                () -> assertEquals(null, c3.media),
                () -> assertEquals(Boolean.FALSE, c3.generateFollowups));
    }

    private void assertEmptyMembersReturnsGuardStringWithoutThrowing() {
        runner = new RecordingAgentOperations();

        String rootId = persistAgent("seq-empty-" + UUID.randomUUID(), "Empty Sequential", true, false,
                "SEQUENTIAL", List.of());
        AgentDefinition rootDef = agentRegistry.findById(rootId, TenantConstants.DEFAULT_SYSTEM_ORG);
        assertNotNull(rootDef);

        String output = sequential.execute(rootDef, "anything", null, "sess-x", "user-x", TenantConstants.DEFAULT_SYSTEM_ORG,
                Boolean.FALSE, runner);

        assertEquals("Team has no members.", output,
                "empty members returns the guard string VERBATIM (no throw) — graceful degradation");
        assertEquals(0, runner.calls.size());
    }

    private void assertInactiveMemberIsFilteredOut() {
        runner = new RecordingAgentOperations();

        String activeM = persistAgent("seq-active-" + UUID.randomUUID(), "Active Stage", true, false, null, null);
        String inactiveM = persistAgent("seq-inactive-" + UUID.randomUUID(), "Inactive Stage", false, false, null, null);
        String maintenanceM = persistAgent("seq-maint-" + UUID.randomUUID(), "Maintenance Stage", true, true, null, null);
        String rootId = persistAgent("seq-mixed-" + UUID.randomUUID(), "Mixed Sequential", true, false,
                "SEQUENTIAL", List.of(activeM, inactiveM, maintenanceM));

        AgentDefinition rootDef = agentRegistry.findById(rootId, TenantConstants.DEFAULT_SYSTEM_ORG);
        assertNotNull(rootDef);

        runner.scriptedResponses.add(runResponse("active output"));

        String output = sequential.execute(rootDef, "kickoff", null, "sess-z", "user-z", TenantConstants.DEFAULT_SYSTEM_ORG,
                Boolean.FALSE, runner);

        assertEquals("active output", output,
                "Sequential drops inactive and maintenance members — only the active member runs");
        assertEquals(1, runner.calls.size(),
                "runner invoked exactly once — inactive and maintenance ids are filtered");
        assertEquals(activeM, runner.calls.get(0).agentId,
                "the active member is the sole dispatch target");
    }

    private RunResponse runResponse(String content) {
        return new RunResponse("run-" + UUID.randomUUID(), "sess-seq", content,
                new HashMap<>(), new ArrayList<>(), new ArrayList<>(), RunStatus.COMPLETED, null);
    }

    private String persistAgent(String id, String name, boolean active, boolean maintenance,
                                String teamMode, List<String> members) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setOrgId(TenantConstants.DEFAULT_SYSTEM_ORG);
        a.setName(name);
        a.setDescription("T029 fixture: " + name);
        a.setInstructions("T029 fixture instructions");
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
            throw new UnsupportedOperationException("minimal run overload not expected in T029");
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
            throw new UnsupportedOperationException("stream not expected in T029");
        }

        @Override
        public String runInBackground(String agentId, String userInput, List<Media> media,
                                       String sessionId, String userId, String orgId,
                                       Boolean generateFollowups, RunOptions options) {
            throw new UnsupportedOperationException("runInBackground not expected in T029");
        }

        @Override
        public String runInBackground(String agentId, String userInput, String sessionId) {
            throw new UnsupportedOperationException("runInBackground (minimal) not expected in T029");
        }

        @Override
        public String runPlayground(String agentId, String userInput, String sessionId) {
            throw new UnsupportedOperationException("runPlayground not expected in T029");
        }

        @Override
        public void cancelRun(String runId) {
            throw new UnsupportedOperationException("cancelRun not expected in T029");
        }

        @Override
        public RunResponse continueRun(String runId, String action) {
            throw new UnsupportedOperationException("continueRun not expected in T029");
        }

        @Override
        public void loadKnowledge(String agentId) {
            throw new UnsupportedOperationException("loadKnowledge not expected in T029");
        }
    }

    private record RecordedCall(String agentId, String userInput, List<Media> media, String sessionId,
                                 String userId, String orgId, Boolean generateFollowups, RunOptions options) {
    }
}
