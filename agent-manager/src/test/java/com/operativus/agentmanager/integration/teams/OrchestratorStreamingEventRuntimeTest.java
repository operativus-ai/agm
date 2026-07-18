package com.operativus.agentmanager.integration.teams;

import com.operativus.agentmanager.compute.service.AgentService;
import com.operativus.agentmanager.control.repository.AgentRepository;
import com.operativus.agentmanager.core.entity.AgentEntity;
import com.operativus.agentmanager.core.model.AgentStreamEvent;
import com.operativus.agentmanager.core.model.EventType;
import com.operativus.agentmanager.core.model.TenantConstants;
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

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: P3 streaming-event contract coverage. Pins the exact
 *   {@link AgentStreamEvent} sequence that {@code TeamOrchestrationEngine.executeStream}
 *   emits for a team run, so future changes to the UI streaming pipeline can't silently
 *   regress the run-tree visualization contract.
 *
 * State: Stateless. Per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}.
 *
 * <h2>The team-stream contract (read from {@code TeamOrchestrationEngine.executeStream}
 * lines 141–190 on main as of merge of PR #444)</h2>
 *
 * Every team run emits a fixed 4-event sequence regardless of orchestrator type:
 *
 * <ol>
 *   <li>{@link EventType#START} — empty data, immediate (line 142)</li>
 *   <li>{@link EventType#TOOL_START} — data is {@code "Initiating <teamMode> Team
 *       Orchestration..."}, immediate (line 143)</li>
 *   <li>{@link EventType#CONTENT_DELTA} — data is the orchestrator's full aggregated
 *       output (line 173); emitted only AFTER the synchronous {@code strategy.execute}
 *       returns</li>
 *   <li>{@link EventType#STOP} — data is the JSON-serialized {@link com.operativus
 *       .agentmanager.core.model.RunResponse} (line 186), or empty on serialization
 *       failure (line 188); the stream completes immediately after this event</li>
 * </ol>
 *
 * <h2>Known limitations of the current contract (NOT bugs — design choices)</h2>
 *
 * <ul>
 *   <li><b>No per-member events bubble up.</b> Member calls (each going through the
 *       advisor chain → ChatClient → ChatModel) emit their OWN stream events, but
 *       those are consumed inside {@code AgentService.run} and not relayed to the
 *       team's outer Flux. UI-side, the user sees a single CONTENT_DELTA carrying
 *       the whole aggregated team output, not progressive member-by-member updates.</li>
 *   <li><b>Aggregate-then-emit.</b> The orchestrator runs synchronously on
 *       {@code boundedElastic} before any CONTENT_DELTA fires. From the user's
 *       perspective: START + TOOL_START arrive instantly, then a long pause while
 *       the team works, then everything arrives at once. Real token-streaming UX
 *       for team runs would require a separate refactor.</li>
 * </ul>
 *
 * These limitations are visible to the user; this test pins the current contract
 * so any change to it (intentional or accidental) requires a deliberate test update.
 *
 * <h2>Test scope</h2>
 *
 * One method per retained orchestrator (Sequential / Planner / Router / Swarm). Each
 * exercises {@code AgentService.stream} (not {@code orchestrator.execute} directly)
 * to verify the contract is wired through the production stream entry point.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class OrchestratorStreamingEventRuntimeTest extends BaseIntegrationTest {

    /** Wall-time bound on a team-stream collection. Generous because the test starts a Spring context. */
    private static final Duration STREAM_TIMEOUT = Duration.ofSeconds(30);

    @Autowired private AgentService agentService;
    @Autowired private AgentRepository agentRepository;
    @Autowired private FakeChatModel fakeChatModel;

    @BeforeEach
    void resetHarness() {
        fakeChatModel.reset();
        seedDefaultModel();
    }

    @Test
    void sequentialTeamStream_emitsStartToolStartContentDeltaStopInOrder() {
        String m1 = persistMember("stream-seq-m1-" + UUID.randomUUID(), "Stage 1");
        String m2 = persistMember("stream-seq-m2-" + UUID.randomUUID(), "Stage 2");
        String m3 = persistMember("stream-seq-m3-" + UUID.randomUUID(), "Stage 3");
        String rootId = persistTeam("stream-seq-root-" + UUID.randomUUID(), "Stream Sequential",
                "SEQUENTIAL", List.of(m1, m2, m3));

        fakeChatModel
                .respondWith("stage 1 out")
                .respondWith("stage 2 out")
                .respondWith("stage 3 final out");

        List<AgentStreamEvent> events = collectStream(rootId, "stream kickoff");

        assertContractFor(events, "SEQUENTIAL", "stage 3 final out");
    }

    @Test
    void plannerTeamStream_emitsStartToolStartContentDeltaStopInOrder() {
        String memberA = persistMember("stream-p-a-" + UUID.randomUUID(), "Researcher");
        String memberB = persistMember("stream-p-b-" + UUID.randomUUID(), "Writer");
        String rootId = persistTeam("stream-p-root-" + UUID.randomUUID(), "Stream Planner",
                "PLANNER", List.of(memberA, memberB));

        fakeChatModel
                .respondWith("""
                        {"steps":[
                          {"stepNumber":1,"targetAgentId":"%s","taskDescription":"gather"},
                          {"stepNumber":2,"targetAgentId":"%s","taskDescription":"write"}
                        ]}
                        """.formatted(memberA, memberB))
                .respondWith("research done")
                .respondWith("draft done")
                .respondWith("planner final synthesis");

        List<AgentStreamEvent> events = collectStream(rootId, "explain X");

        assertContractFor(events, "PLANNER", "planner final synthesis");
    }

    @Test
    void routerTeamStream_emitsStartToolStartContentDeltaStopInOrder() {
        String memberA = persistMember("stream-r-a-" + UUID.randomUUID(), "Billing");
        String memberB = persistMember("stream-r-b-" + UUID.randomUUID(), "Tech Support");
        String rootId = persistTeam("stream-r-root-" + UUID.randomUUID(), "Stream Router",
                "ROUTER", List.of(memberA, memberB));

        fakeChatModel
                .respondWith("{\"targetAgentId\":\"" + memberB + "\",\"rationale\":\"tech\"}")
                .respondWith("router-routed-content");

        List<AgentStreamEvent> events = collectStream(rootId, "server is down");

        assertContractFor(events, "ROUTER", "router-routed-content");
    }

    @Test
    void swarmTeamStream_emitsStartToolStartContentDeltaStopInOrder() {
        String mA = persistMember("stream-s-a-" + UUID.randomUUID(), "Worker A");
        String mB = persistMember("stream-s-b-" + UUID.randomUUID(), "Worker B");
        String mC = persistMember("stream-s-c-" + UUID.randomUUID(), "Worker C");
        String rootId = persistTeam("stream-s-root-" + UUID.randomUUID(), "Stream Swarm",
                "SWARM", List.of(mA, mB, mC));

        String decomposition = """
                {"rationale":"three-subtasks",
                 "subtasks":[
                   {"targetAgentId":"%s","specificQuery":"alpha"},
                   {"targetAgentId":"%s","specificQuery":"beta"},
                   {"targetAgentId":"%s","specificQuery":"gamma"}
                 ]}
                """.formatted(mA, mB, mC);

        java.util.function.Function<Prompt, ChatResponse> dispatcher = p -> {
            String text = promptText(p);
            String content;
            if (text.contains("alpha")) content = "alpha out";
            else if (text.contains("beta")) content = "beta out";
            else if (text.contains("gamma")) content = "gamma out";
            else content = decomposition;
            return new ChatResponse(List.of(
                    new Generation(new AssistantMessage(content),
                            ChatGenerationMetadata.builder().finishReason("STOP").build())));
        };
        for (int i = 0; i < 4; i++) {
            fakeChatModel.respondWith(dispatcher);
        }

        List<AgentStreamEvent> events = collectStream(rootId, "complex request");

        // Swarm's CONTENT_DELTA carries the synthesis aggregation header + each worker's content,
        // so we can't pin it to a single string — assert the contract shape and sample content presence.
        assertAll("Swarm team-stream contract",
                () -> assertEquals(4, events.size(), "exactly 4 events"),
                () -> assertEquals(EventType.START, events.get(0).event()),
                () -> assertEquals(EventType.TOOL_START, events.get(1).event()),
                () -> assertTrue(events.get(1).data().contains("SWARM"),
                        "TOOL_START data names the orchestrator type"),
                () -> assertEquals(EventType.CONTENT_DELTA, events.get(2).event()),
                () -> assertTrue(events.get(2).data().contains("alpha out"),
                        "CONTENT_DELTA includes worker alpha"),
                () -> assertTrue(events.get(2).data().contains("beta out"),
                        "CONTENT_DELTA includes worker beta"),
                () -> assertTrue(events.get(2).data().contains("gamma out"),
                        "CONTENT_DELTA includes worker gamma"),
                () -> assertEquals(EventType.STOP, events.get(3).event()),
                () -> assertFalse(events.get(3).data().isEmpty(),
                        "STOP carries serialized RunResponse JSON (non-empty on success path)"));
    }

    /**
     * Asserts the strict 4-event team-stream contract. CONTENT_DELTA's data is matched
     * via {@code contains} on the expected output substring rather than equals because
     * Planner's synthesis goes through the planner's own ChatClient which may add
     * decoration; for Sequential/Router the data IS the literal output.
     */
    private void assertContractFor(List<AgentStreamEvent> events, String teamMode, String expectedOutputContains) {
        assertAll("team-stream contract for " + teamMode,
                () -> assertEquals(4, events.size(),
                        "exactly 4 events: START, TOOL_START, CONTENT_DELTA, STOP — got " +
                                events.stream().map(e -> e.event().name()).toList()),
                () -> assertEquals(EventType.START, events.get(0).event(),
                        "first event is START"),
                () -> assertEquals(EventType.TOOL_START, events.get(1).event(),
                        "second event is TOOL_START"),
                () -> assertTrue(events.get(1).data().contains(teamMode),
                        "TOOL_START data names the orchestrator type"),
                () -> assertEquals(EventType.CONTENT_DELTA, events.get(2).event(),
                        "third event is CONTENT_DELTA carrying the orchestrator's aggregate output"),
                () -> assertTrue(events.get(2).data().contains(expectedOutputContains),
                        "CONTENT_DELTA contains the expected output substring '" + expectedOutputContains
                                + "' but was: " + events.get(2).data()),
                () -> assertEquals(EventType.STOP, events.get(3).event(),
                        "fourth event is STOP"),
                () -> assertFalse(events.get(3).data().isEmpty(),
                        "STOP carries serialized RunResponse JSON (non-empty on success path)"));
    }

    private List<AgentStreamEvent> collectStream(String rootId, String input) {
        List<AgentStreamEvent> events = agentService
                .stream(rootId, input, null, "stream-sess-" + UUID.randomUUID(), "stream-user",
                        TenantConstants.DEFAULT_SYSTEM_ORG, Boolean.FALSE, null)
                .collectList()
                .block(STREAM_TIMEOUT);
        assertNotNull(events, "stream must complete and produce a non-null event list");
        return events;
    }

    private static String promptText(Prompt p) {
        StringBuilder sb = new StringBuilder();
        for (var m : p.getInstructions()) {
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
        a.setDescription("P3 streaming fixture: " + name);
        a.setInstructions("P3 fixture instructions");
        a.setModelId("gpt-4o-mini");
        a.setActive(true);
        a.setMaintenanceMode(false);
        a.setTeam(isTeam);
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
