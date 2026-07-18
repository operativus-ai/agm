package com.operativus.agentmanager.integration.teams;

import com.operativus.agentmanager.control.repository.AgentRepository;
import com.operativus.agentmanager.core.entity.AgentEntity;
import com.operativus.agentmanager.core.model.TenantConstants;
import com.operativus.agentmanager.core.model.definitions.AgentDefinition;
import com.operativus.agentmanager.core.model.definitions.AgentRegistry;
import com.operativus.agentmanager.core.registry.AgentOperations;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModel;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
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
 * Domain Responsibility: Boot-context runtime coverage of NESTED orchestration —
 *   one team agent (root) whose members include another team agent (inner). The
 *   prior orchestration runtime tests (PRs #435–#440) all use a recording double
 *   for {@link AgentOperations} so they can pin orchestrator-internal behavior in
 *   isolation. This file is different: it enters through the production front door
 *   {@code agentOperations.run(outerId, ...)} (the real Spring-resolved
 *   {@code AgentService} bean). {@code AgentService.run} sees {@code teamMode!=null}
 *   and routes through {@code TeamOrchestrationEngine.executeSync}, which binds the
 *   {@code orchestrationDepth} ScopedValue and invokes the outer orchestrator with
 *   the same real runner. When that orchestrator dispatches to an inner team-agent
 *   member, the same path re-enters recursively — recursion happens through the SPI
 *   seam at the {@code AgentOperations} interface, not through explicit composition
 *   glue. Entering via {@code run} (not {@code strategy.execute} directly) is what
 *   binds {@code orchestrationDepth > 0} for the outer team, so the cross-agent
 *   session-ownership guard in {@code AgentService.ensureSessionExists} correctly
 *   admits the outer team's own members.
 *
 *   The three tests pin three deterministic compositions —
 *   SEQUENTIAL → SEQUENTIAL, PLANNER → SEQUENTIAL, and ROUTER → SWARM — and prove
 *   the recursion works end-to-end against {@link FakeChatModel} (each leaf consumes
 *   one fake chat response; the Router/Swarm/Planner outers also consume their own
 *   decision/decomposition/synthesis responses).
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 *
 * Implementation notes / why this works:
 *   - {@code AgentService.run} routes to {@code TeamOrchestrationEngine.executeSync}
 *     when the resolved {@link AgentDefinition} has a non-null {@code teamMode}.
 *   - {@code TeamOrchestrationEngine.executeSync} calls {@code strategy.execute(def, ..., this)}
 *     where {@code this} is the {@code AgentOperations} runner — so nested team
 *     dispatches re-enter {@code AgentService.run} recursively.
 *   - Leaf (non-team) agent runs go through the full advisor chain + a real
 *     {@code ChatClient} backed by {@link FakeChatModel}. One scripted response
 *     per leaf invocation.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class,
        NoOpReflectionServiceConfig.class})
public class OrchestratorCompositionRuntimeTest extends BaseIntegrationTest {

    @Autowired private AgentRegistry agentRegistry;
    @Autowired private AgentRepository agentRepository;
    @Autowired private FakeChatModel fakeChatModel;
    /**
     * Resolved by Spring to the production {@code AgentService} bean. Each test
     * enters through {@code agentOperations.run(outerId, ...)} — the production
     * front door — so the outer team is wrapped in {@code TeamOrchestrationEngine.executeSync}
     * (which binds the {@code orchestrationDepth} ScopedValue). When a member is
     * itself a team, {@code AgentService.run} re-routes through the engine again,
     * recursing through the {@code AgentOperations} SPI seam.
     */
    @Autowired private AgentOperations agentOperations;

    @BeforeEach
    void resetHarness() {
        fakeChatModel.reset();
        seedDefaultModel();
    }

    /**
     * Sequential outer with members [innerSeq, leafEnd]. innerSeq is a Sequential
     * team with members [leafA, leafB]. Calling outer.execute should:
     *   1. Dispatch member 1 (innerSeq) via real AgentService.run → routes to
     *      TeamOrchestrationEngine → invokes Sequential strategy with the same
     *      AgentOperations runner.
     *   2. innerSeq dispatches leafA → AgentService.run → leaf agent path →
     *      advisor chain → FakeChatModel returns "leafA-result".
     *   3. innerSeq dispatches leafB with leafA's content as input →
     *      FakeChatModel returns "leafB-result".
     *   4. innerSeq returns "leafB-result" up to the outer Sequential.
     *   5. Outer Sequential dispatches leafEnd with "leafB-result" as input →
     *      FakeChatModel returns "end-result".
     *   6. Outer Sequential returns "end-result" as the final output.
     *
     * Total fake-LLM consumption: 3 responses, in order: leafA, leafB, leafEnd.
     */
    @Test
    void sequentialOuter_withSequentialInnerAsMember_dispatchesNestedAndReturnsLeafEndContent() {
        String leafA = persistLeaf("comp-leaf-a-" + UUID.randomUUID(), "Leaf A");
        String leafB = persistLeaf("comp-leaf-b-" + UUID.randomUUID(), "Leaf B");
        String innerSeq = persistTeam("comp-inner-" + UUID.randomUUID(), "Inner Seq",
                "SEQUENTIAL", List.of(leafA, leafB));

        String leafEnd = persistLeaf("comp-leaf-end-" + UUID.randomUUID(), "Leaf End");
        String outerId = persistTeam("comp-outer-" + UUID.randomUUID(), "Outer Seq",
                "SEQUENTIAL", List.of(innerSeq, leafEnd));

        AgentDefinition outerDef = agentRegistry.findById(outerId, TenantConstants.DEFAULT_SYSTEM_ORG);
        assertNotNull(outerDef);

        // 3 fake responses consumed in sequence — one per leaf agent run.
        fakeChatModel.respondWith("leafA-result");
        fakeChatModel.respondWith("leafB-result");
        fakeChatModel.respondWith("end-result");

        String output = agentOperations.run(outerId, "initial-input", null,
                "sess-comp", "user-comp", TenantConstants.DEFAULT_SYSTEM_ORG, Boolean.TRUE, null).content();

        assertAll("nested Sequential composition",
                () -> assertEquals("end-result", output,
                        "outer Sequential returns the LAST member's content (leafEnd's response)"),
                () -> assertEquals(3, fakeChatModel.receivedPrompts().size(),
                        "exactly 3 leaf LLM calls: leafA, leafB, leafEnd"),
                () -> assertTrue(persistedRunCount() >= 3,
                        "real AgentService persists at least one agent_runs row per leaf invocation "
                                + "(actual count includes inner orchestration overhead)"));
    }

    /**
     * Planner outer with Sequential inner. Outer Planner has members [innerSeq, leafEnd].
     * The plan delegates step 1 to innerSeq, step 2 to leafEnd. innerSeq is a Sequential
     * team with [leafA, leafB].
     *
     * FakeChatModel script (5 prompts in this exact order):
     *   1. Planner's plan JSON
     *   2. leafA's call → "leafA-research"
     *   3. leafB's call (chained input from leafA) → "leafB-research-summary"
     *   4. leafEnd's call (input from innerSeq's last output) → "polished-final"
     *   5. Planner's synthesis call → "planner-synthesis"
     */
    @Test
    void plannerOuter_withSequentialInnerAsStepTarget_executesNestedAndReturnsSynthesis() {
        String leafA = persistLeaf("comp-p-a-" + UUID.randomUUID(), "Researcher A");
        String leafB = persistLeaf("comp-p-b-" + UUID.randomUUID(), "Researcher B");
        String innerSeq = persistTeam("comp-p-inner-" + UUID.randomUUID(), "Inner Seq",
                "SEQUENTIAL", List.of(leafA, leafB));

        String leafEnd = persistLeaf("comp-p-end-" + UUID.randomUUID(), "Polisher");
        String outerId = persistTeam("comp-p-outer-" + UUID.randomUUID(), "Outer Planner",
                "PLANNER", List.of(innerSeq, leafEnd));

        AgentDefinition outerDef = agentRegistry.findById(outerId, TenantConstants.DEFAULT_SYSTEM_ORG);
        assertNotNull(outerDef);

        fakeChatModel
                .respondWith("""
                        {"steps":[
                          {"stepNumber":1,"targetAgentId":"%s","taskDescription":"research topic"},
                          {"stepNumber":2,"targetAgentId":"%s","taskDescription":"polish the research"}
                        ]}
                        """.formatted(innerSeq, leafEnd))
                .respondWith("leafA-research")
                .respondWith("leafB-research-summary")
                .respondWith("polished-final")
                .respondWith("planner-synthesis");

        String output = agentOperations.run(outerId, "explain composition", null,
                "sess-comp-p", "user-comp-p", TenantConstants.DEFAULT_SYSTEM_ORG,
                Boolean.FALSE, null).content();

        assertAll("Planner→Sequential composition",
                () -> assertEquals("planner-synthesis", output,
                        "Planner returns its synthesis, not raw step outputs"),
                () -> assertEquals(5, fakeChatModel.receivedPrompts().size(),
                        "5 LLM calls: plan + leafA + leafB + leafEnd + synthesis"),
                () -> assertTrue(persistedRunCount() >= 3,
                        "at least 3 leaf agent_runs persisted (plus inner + outer team rows)"));
    }

    /**
     * Router outer with Swarm inner. Outer Router has members [innerSwarm, leafAlt].
     * The Router LLM picks innerSwarm. innerSwarm is a Swarm team with [leafA, leafB,
     * leafC]; the Swarm orchestrator-internal LLM call returns a 3-subtask
     * decomposition; each subtask runs concurrently via real AgentService.
     *
     * Uses a single content-aware Function (registered 5 times) to side-step
     * ArrayDeque non-thread-safety under concurrent worker polls.
     */
    @Test
    void routerOuter_withSwarmInnerAsRoutedTarget_executesNestedConcurrentlyAndAggregates() {
        String leafA = persistLeaf("comp-r-a-" + UUID.randomUUID(), "Worker A");
        String leafB = persistLeaf("comp-r-b-" + UUID.randomUUID(), "Worker B");
        String leafC = persistLeaf("comp-r-c-" + UUID.randomUUID(), "Worker C");
        String innerSwarm = persistTeam("comp-r-inner-" + UUID.randomUUID(), "Inner Swarm",
                "SWARM", List.of(leafA, leafB, leafC));

        String leafAlt = persistLeaf("comp-r-alt-" + UUID.randomUUID(), "Alternate Specialist");
        String outerId = persistTeam("comp-r-outer-" + UUID.randomUUID(), "Outer Router",
                "ROUTER", List.of(innerSwarm, leafAlt));

        AgentDefinition outerDef = agentRegistry.findById(outerId, TenantConstants.DEFAULT_SYSTEM_ORG);
        assertNotNull(outerDef);

        String routerDecisionJson = "{\"targetAgentId\":\"" + innerSwarm + "\",\"rationale\":\"swarm fits best\"}";
        String swarmDecompositionJson = """
                {"rationale":"three-subtasks",
                 "subtasks":[
                   {"targetAgentId":"%s","specificQuery":"alpha"},
                   {"targetAgentId":"%s","specificQuery":"beta"},
                   {"targetAgentId":"%s","specificQuery":"gamma"}
                 ]}
                """.formatted(leafA, leafB, leafC);

        java.util.function.Function<Prompt, ChatResponse> dispatcher = p -> {
            String text = promptText(p);
            String content;
            if (text.contains("alpha")) content = "alpha-out";
            else if (text.contains("beta")) content = "beta-out";
            else if (text.contains("gamma")) content = "gamma-out";
            else if (text.contains("subtasks") || text.contains("Swarm Orchestrator")) content = swarmDecompositionJson;
            else content = routerDecisionJson;
            return new ChatResponse(List.of(
                    new Generation(new AssistantMessage(content),
                            ChatGenerationMetadata.builder().finishReason("STOP").build())));
        };
        for (int i = 0; i < 5; i++) {
            fakeChatModel.respondWith(dispatcher);
        }

        String output = agentOperations.run(outerId, "complex multi-part request", null,
                "sess-comp-r", "user-comp-r", TenantConstants.DEFAULT_SYSTEM_ORG,
                Boolean.FALSE, null).content();

        assertAll("Router→Swarm composition",
                () -> assertNotNull(output, "Router returns the inner Swarm's aggregated output"),
                () -> assertTrue(output.contains("alpha-out"),
                        "aggregation embeds worker A's content (proves nested Swarm fan-out completed)"),
                () -> assertTrue(output.contains("beta-out"),
                        "aggregation embeds worker B's content"),
                () -> assertTrue(output.contains("gamma-out"),
                        "aggregation embeds worker C's content"),
                () -> assertEquals(5, fakeChatModel.receivedPrompts().size(),
                        "5 LLM calls: router decision + swarm decomposition + 3 concurrent workers"),
                () -> assertTrue(persistedRunCount() >= 3,
                        "at least 3 leaf agent_runs persisted (plus inner + outer team rows)"));
    }

    private static String promptText(Prompt p) {
        StringBuilder sb = new StringBuilder();
        for (var m : p.getInstructions()) {
            sb.append(m.getText()).append('\n');
        }
        return sb.toString();
    }

    private long persistedRunCount() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM agent_runs", Long.class);
        return count != null ? count : 0L;
    }

    private String persistLeaf(String id, String name) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setOrgId(TenantConstants.DEFAULT_SYSTEM_ORG);
        a.setName(name);
        a.setDescription("composition-leaf fixture: " + name);
        a.setInstructions("composition-leaf fixture instructions");
        a.setModelId("gpt-4o-mini");
        a.setActive(true);
        a.setMaintenanceMode(false);
        return agentRepository.save(a).getId();
    }

    private String persistTeam(String id, String name, String teamMode, List<String> members) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setOrgId(TenantConstants.DEFAULT_SYSTEM_ORG);
        a.setName(name);
        a.setDescription("composition-team fixture: " + name);
        a.setInstructions("composition-team fixture instructions");
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
}
