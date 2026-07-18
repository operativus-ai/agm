package com.operativus.agentmanager.integration.teams;

import com.operativus.agentmanager.compute.service.AgentService;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.compute.teams.PlannerOrchestrator;
import com.operativus.agentmanager.compute.teams.RouterOrchestrator;
import com.operativus.agentmanager.compute.teams.SequentialOrchestrator;
import com.operativus.agentmanager.compute.teams.SwarmOrchestrator;
import com.operativus.agentmanager.control.repository.AgentRepository;
import com.operativus.agentmanager.core.entity.AgentEntity;
import com.operativus.agentmanager.core.model.TenantConstants;
import com.operativus.agentmanager.core.model.definitions.AgentDefinition;
import com.operativus.agentmanager.core.model.definitions.AgentRegistry;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModel;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: end-to-end orchestration runtime coverage where the leaf
 *   {@code AgentOperations} runner is the production {@link AgentService} (not a test
 *   double). Closes the gap that the per-orchestrator runtime tests leave: those use
 *   {@code RecordingAgentOperations}, proving the orchestrator's dispatch + wait logic
 *   but bypassing the real single-agent execution path (advisor chain, ChatClient
 *   build, DB run-row creation, ScopedValue propagation). This test wires production
 *   {@code AgentService} at the runner seam and {@link FakeChatModel} at the LLM seam,
 *   then asserts the full end-to-end shape.
 *
 * State: Stateless. Per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}.
 *
 * First case scope (Sequential, 3 members):
 *   - All 3 members invoke the real {@link AgentService} dispatch path. Verified via
 *     {@link FakeChatModel#receivedPrompts()} count — proves every member touched the
 *     real LLM seam, not a stub.
 *   - Chain propagation is real: member N's user prompt contains member N-1's content,
 *     having traversed the production advisor chain.
 *   - DB persistence is real: agent_runs has one row per member with status=COMPLETED.
 *   - Sequential's return value is the last member's content (matches the contract
 *     pinned by {@code SequentialOrchestratorRuntimeTest}).
 *
 * Why a separate test class (vs. extending {@code SequentialOrchestratorRuntimeTest}):
 *   the existing tests use a deliberately-narrow runner double to keep their scope on
 *   orchestrator semantics. This test deliberately broadens scope to surface real
 *   wiring interactions — failure here can mean a real bug in any of: AgentService,
 *   AgentClientFactory, the advisor chain, the run-row persistence path, or the
 *   ScopedValue plumbing — and that breadth is the point.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class EndToEndOrchestrationRuntimeTest extends BaseIntegrationTest {

    @Autowired private SequentialOrchestrator sequential;
    @Autowired private PlannerOrchestrator planner;
    @Autowired private RouterOrchestrator router;
    @Autowired private SwarmOrchestrator swarm;
    @Autowired private AgentService agentService; // production runner — the whole point of this test
    @Autowired private AgentRegistry agentRegistry;
    @Autowired private AgentRepository agentRepository;
    @Autowired private FakeChatModel fakeChatModel;

    @BeforeEach
    void resetHarness() {
        fakeChatModel.reset();
        seedDefaultModel();
    }

    /**
     * E2E case 1 — Sequential 3-member chain dispatched through production
     * {@link AgentService}. Single {@code @Test} via {@code assertAll} pinning every
     * cross-layer invariant the real path is supposed to honor.
     */
    @Test
    void sequential3Members_endToEnd_eachMemberHitsAgentServiceAndChainOutputsPropagate() {
        String m1 = persistMember("e2e-m1-" + UUID.randomUUID(), "Stage 1");
        String m2 = persistMember("e2e-m2-" + UUID.randomUUID(), "Stage 2");
        String m3 = persistMember("e2e-m3-" + UUID.randomUUID(), "Stage 3");
        String rootId = persistTeam("e2e-root-" + UUID.randomUUID(), "E2E Sequential Root",
                "SEQUENTIAL", List.of(m1, m2, m3));

        AgentDefinition rootDef = agentRegistry.findById(rootId, TenantConstants.DEFAULT_SYSTEM_ORG);
        assertNotNull(rootDef, "rootDef must resolve via AgentRegistry");

        // Each scripted response is what the real AgentService will return up the stack
        // for that member's invocation. Because Sequential's contract is "next member's
        // input == prior member's content," member 2's prompt MUST surface "stage 1
        // response" and member 3's MUST surface "stage 2 response".
        fakeChatModel
                .respondWith("stage 1 response")
                .respondWith("stage 2 response")
                .respondWith("stage 3 final response");

        String sessionId = "e2e-sess-" + UUID.randomUUID();
        String output = withOrchestrationDepth(() -> sequential.execute(rootDef, "kickoff", null,
                sessionId, "e2e-user", TenantConstants.DEFAULT_SYSTEM_ORG,
                Boolean.FALSE, agentService));

        List<Prompt> prompts = fakeChatModel.receivedPrompts();

        Integer memberRunRowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_runs WHERE agent_id IN (?, ?, ?)",
                Integer.class, m1, m2, m3);
        Integer memberCompletedCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_runs " +
                        "WHERE agent_id IN (?, ?, ?) AND status = 'COMPLETED'",
                Integer.class, m1, m2, m3);

        assertAll("E2E Sequential — 3 members dispatched through production AgentService",
                () -> assertEquals("stage 3 final response", output,
                        "Sequential returns the last member's content (real path preserves the contract)"),
                () -> assertEquals(3, prompts.size(),
                        "FakeChatModel received exactly 3 prompts — one per member, " +
                                "proving every member traversed the real AgentService -> ChatClient seam"),
                () -> assertTrue(promptUserText(prompts.get(0)).contains("kickoff"),
                        "member 1 receives the orchestrator's initial input through the advisor chain"),
                () -> assertTrue(promptUserText(prompts.get(1)).contains("stage 1 response"),
                        "member 2's prompt contains member 1's content — chain propagation through real path"),
                () -> assertTrue(promptUserText(prompts.get(2)).contains("stage 2 response"),
                        "member 3's prompt contains member 2's content — chain propagation through real path"),
                () -> assertEquals(3, memberRunRowCount,
                        "agent_runs has one row per member — production AgentService persisted real run rows"),
                () -> assertEquals(3, memberCompletedCount,
                        "every member run reached status=COMPLETED — full end-to-end success"));
    }

    /**
     * E2E case 2 — Planner 2-member full lifecycle (plan -> solve -> synthesize) where
     * each plan step is dispatched through the production {@link AgentService}.
     *
     * The Planner consults the LLM TWICE per execute() — once for the structured plan,
     * once for synthesis. Each plan step also consults the LLM once via
     * {@code AgentService.run}. With 2 members that's 4 total LLM calls:
     *   prompt[0] = orchestrator's plan request → JSON {steps:[...]}
     *   prompt[1] = member A's call (real AgentService dispatch) → "stage A result"
     *   prompt[2] = member B's call (real AgentService dispatch) → "stage B result"
     *   prompt[3] = orchestrator's synthesis call → "final synthesis"
     */
    @Test
    void planner2Members_endToEnd_planThenSolveThenSynthesizeThroughRealAgentService() {
        String memberA = persistMember("e2e-p-a-" + UUID.randomUUID(), "Researcher");
        String memberB = persistMember("e2e-p-b-" + UUID.randomUUID(), "Writer");
        String rootId = persistTeam("e2e-p-root-" + UUID.randomUUID(), "E2E Planner Root",
                "PLANNER", List.of(memberA, memberB));

        AgentDefinition rootDef = agentRegistry.findById(rootId, TenantConstants.DEFAULT_SYSTEM_ORG);
        assertNotNull(rootDef);

        fakeChatModel
                .respondWith("""
                        {"steps":[
                          {"stepNumber":1,"targetAgentId":"%s","taskDescription":"gather research"},
                          {"stepNumber":2,"targetAgentId":"%s","taskDescription":"write summary"}
                        ]}
                        """.formatted(memberA, memberB))
                .respondWith("research findings")
                .respondWith("summary draft")
                .respondWith("final synthesis");

        String output = withOrchestrationDepth(() -> planner.execute(rootDef, "explain topic X", null,
                "e2e-p-sess-" + UUID.randomUUID(), "e2e-p-user",
                TenantConstants.DEFAULT_SYSTEM_ORG, Boolean.FALSE, agentService));

        List<Prompt> prompts = fakeChatModel.receivedPrompts();
        Integer memberRuns = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_runs WHERE agent_id IN (?, ?) AND status = 'COMPLETED'",
                Integer.class, memberA, memberB);

        assertAll("E2E Planner — 2 members through production AgentService",
                () -> assertEquals("final synthesis", output,
                        "Planner returns its Phase-3 synthesis content"),
                () -> assertEquals(4, prompts.size(),
                        "FakeChatModel saw 4 prompts: plan + 2 members + synthesis"),
                () -> assertTrue(promptUserText(prompts.get(1)).contains("gather research"),
                        "member A's prompt carries plan.steps[0].taskDescription"),
                () -> assertTrue(promptUserText(prompts.get(2)).contains("write summary"),
                        "member B's prompt carries plan.steps[1].taskDescription"),
                () -> assertEquals(2, memberRuns,
                        "agent_runs has 2 COMPLETED rows — one per planned member"));
    }

    /**
     * E2E case 3 — Router decision dispatches exactly one real member through
     * production {@link AgentService}.
     *
     * Router pattern:
     *   prompt[0] = orchestrator's routing decision → JSON {targetAgentId, rationale}
     *   prompt[1] = the chosen member's call (real AgentService dispatch)
     */
    @Test
    void router2Members_endToEnd_decisionDispatchesOneRealMember() {
        String memberA = persistMember("e2e-r-a-" + UUID.randomUUID(), "Billing");
        String memberB = persistMember("e2e-r-b-" + UUID.randomUUID(), "Tech Support");
        String rootId = persistTeam("e2e-r-root-" + UUID.randomUUID(), "E2E Router Root",
                "ROUTER", List.of(memberA, memberB));

        AgentDefinition rootDef = agentRegistry.findById(rootId, TenantConstants.DEFAULT_SYSTEM_ORG);
        assertNotNull(rootDef);

        fakeChatModel
                .respondWith("{\"targetAgentId\":\"" + memberB + "\",\"rationale\":\"tech issue\"}")
                .respondWith("tech support resolution");

        String output = withOrchestrationDepth(() -> router.execute(rootDef, "my server is down", null,
                "e2e-r-sess-" + UUID.randomUUID(), "e2e-r-user",
                TenantConstants.DEFAULT_SYSTEM_ORG, Boolean.FALSE, agentService));

        List<Prompt> prompts = fakeChatModel.receivedPrompts();
        Integer aRuns = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_runs WHERE agent_id = ?", Integer.class, memberA);
        Integer bRuns = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_runs WHERE agent_id = ? AND status = 'COMPLETED'",
                Integer.class, memberB);

        assertAll("E2E Router — decision selects exactly one real member",
                () -> assertEquals("tech support resolution", output,
                        "Router returns the routed-to member's content unchanged"),
                () -> assertEquals(2, prompts.size(),
                        "FakeChatModel saw 2 prompts: decision + 1 dispatched member"),
                () -> assertEquals(0, aRuns,
                        "non-routed member A was NOT dispatched — zero run rows"),
                () -> assertEquals(1, bRuns,
                        "routed member B was dispatched and reached COMPLETED"));
    }

    /**
     * E2E case 4 — Swarm fans out to multiple workers concurrently, each invoking
     * the real {@link AgentService}. The orchestrator awaits all workers via
     * {@code Future.get()} before aggregating.
     *
     * Swarm pattern:
     *   prompt[0] = orchestrator's decomposition (synchronous, before fan-out)
     *   prompt[1..N] = N concurrent worker calls (order non-deterministic)
     *
     * Concurrency note: {@link FakeChatModel}'s scripted-response deque is not strictly
     * thread-safe under concurrent {@code poll}. We side-step by registering a single
     * content-aware Function-variant that maps prompt text to the response, then
     * registering it once per expected concurrent call so the deque has enough entries.
     * Because the function is deterministic per prompt content, a benign race that
     * mis-orders polls still yields correct per-call answers.
     */
    @Test
    void swarm3Members_endToEnd_concurrentWorkersAllInvokeRealAgentService() {
        String memberA = persistMember("e2e-s-a-" + UUID.randomUUID(), "Worker A");
        String memberB = persistMember("e2e-s-b-" + UUID.randomUUID(), "Worker B");
        String memberC = persistMember("e2e-s-c-" + UUID.randomUUID(), "Worker C");
        String rootId = persistTeam("e2e-s-root-" + UUID.randomUUID(), "E2E Swarm Root",
                "SWARM", List.of(memberA, memberB, memberC));

        AgentDefinition rootDef = agentRegistry.findById(rootId, TenantConstants.DEFAULT_SYSTEM_ORG);
        assertNotNull(rootDef);

        String decompositionJson = """
                {"rationale":"three-parallel-subtasks",
                 "subtasks":[
                   {"targetAgentId":"%s","specificQuery":"subtask alpha"},
                   {"targetAgentId":"%s","specificQuery":"subtask beta"},
                   {"targetAgentId":"%s","specificQuery":"subtask gamma"}
                 ]}
                """.formatted(memberA, memberB, memberC);

        java.util.function.Function<Prompt, org.springframework.ai.chat.model.ChatResponse> dispatcher = p -> {
            String txt = promptUserText(p);
            String content;
            if (txt.contains("subtask alpha")) content = "alpha result";
            else if (txt.contains("subtask beta")) content = "beta result";
            else if (txt.contains("subtask gamma")) content = "gamma result";
            else content = decompositionJson;
            return new org.springframework.ai.chat.model.ChatResponse(java.util.List.of(
                    new org.springframework.ai.chat.model.Generation(
                            new org.springframework.ai.chat.messages.AssistantMessage(content),
                            org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder()
                                    .finishReason("STOP").build())));
        };
        // 1 orchestrator call + 3 concurrent worker calls = need 4 deque entries
        for (int i = 0; i < 4; i++) {
            fakeChatModel.respondWith(dispatcher);
        }

        String output = withOrchestrationDepth(() -> swarm.execute(rootDef, "complex multi-part request", null,
                "e2e-s-sess-" + UUID.randomUUID(), "e2e-s-user",
                TenantConstants.DEFAULT_SYSTEM_ORG, Boolean.FALSE, agentService));

        List<Prompt> prompts = fakeChatModel.receivedPrompts();
        Integer completedRuns = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_runs WHERE agent_id IN (?, ?, ?) AND status = 'COMPLETED'",
                Integer.class, memberA, memberB, memberC);

        assertAll("E2E Swarm — 3 concurrent workers through production AgentService",
                () -> assertEquals(4, prompts.size(),
                        "FakeChatModel saw 4 prompts: 1 decomposition + 3 concurrent workers"),
                () -> assertTrue(output.contains("alpha result"),
                        "aggregation embeds worker A's content"),
                () -> assertTrue(output.contains("beta result"),
                        "aggregation embeds worker B's content"),
                () -> assertTrue(output.contains("gamma result"),
                        "aggregation embeds worker C's content"),
                () -> assertEquals(3, completedRuns,
                        "agent_runs has 3 COMPLETED rows — every concurrent worker persisted a real run"));
    }

    /**
     * Extracts the concatenated text of all messages in a prompt. The user-facing input
     * may sit on the user message; the system message is added by the advisor chain.
     * We use {@code contains} on the joined text so this does not over-pin the exact
     * message structure (which can shift with advisor changes).
     */
    private static String promptUserText(Prompt p) {
        StringBuilder sb = new StringBuilder();
        for (Message m : p.getInstructions()) {
            sb.append(m.getText()).append('\n');
        }
        return sb.toString();
    }

    private String persistMember(String id, String name) {
        return persist(id, name, false, null, null);
    }

    private String persistTeam(String id, String name, String teamMode, List<String> members) {
        return persist(id, name, true, teamMode, members);
    }

    private String persist(String id, String name, boolean isTeam, String teamMode, List<String> members) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setOrgId(TenantConstants.DEFAULT_SYSTEM_ORG);
        a.setName(name);
        a.setDescription("E2E orchestration fixture: " + name);
        a.setInstructions("E2E fixture instructions");
        a.setModelId("gpt-4o-mini");
        a.setActive(true);
        a.setMaintenanceMode(false);
        a.setTeam(isTeam);
        a.setTeamMode(teamMode);
        a.setMembers(members);
        return agentRepository.save(a).getId();
    }

    /**
     * Wraps an orchestrator invocation in {@code orchestrationDepth=1} so
     * {@code AgentService.ensureSessionExists} treats member dispatches as
     * "dispatched by team orchestrator" — its agent_id-mismatch arm requires
     * {@code AgentContextHolder.getOrchestrationDepth() > 0} to allow re-using
     * a session row across member ids. In production, this binding is supplied
     * by {@code TeamOrchestrationEngine}; this test bypasses that and calls
     * orchestrators directly so it must replicate the contract.
     */
    private String withOrchestrationDepth(java.util.function.Supplier<String> body) {
        try {
            return ScopedValue.where(AgentContextHolder.orchestrationDepth, 1).call(body::get);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void seedDefaultModel() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }
}
