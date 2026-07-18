package ai.operativus.agentmanager.integration.teams;

import ai.operativus.agentmanager.compute.service.AgentService;
import ai.operativus.agentmanager.control.repository.AgentRepository;
import ai.operativus.agentmanager.core.entity.AgentEntity;
import ai.operativus.agentmanager.core.model.AgentStreamEvent;
import ai.operativus.agentmanager.core.model.EventType;
import ai.operativus.agentmanager.core.model.TenantConstants;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModel;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import ai.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins the error-path contract of
 *   {@link ai.operativus.agentmanager.compute.service.TeamOrchestrationEngine#executeStream}.
 *   Sibling {@code OrchestratorStreamingEventRuntimeTest} covers the success path
 *   (4-event START → TOOL_START → CONTENT_DELTA → STOP); this class covers what
 *   happens when {@code strategy.execute} throws inside the boundedElastic worker.
 *
 *   <p>Current contract (from {@code TeamOrchestrationEngine:177}):
 *   <ul>
 *     <li>START and TOOL_START events are emitted synchronously inside {@code Flux.create}
 *         BEFORE the boundedElastic scheduler dispatches — subscribers always see them.</li>
 *     <li>When {@code strategy.execute} throws, the catch block calls
 *         {@code agentRunFinalizer.finalizeRun(executionRunId, FAILED, "Error: " + message)}
 *         then {@code sink.error(e)} — terminating the Flux with a reactive error,
 *         NOT an {@link EventType#ERROR} event.</li>
 *     <li>The {@code agent_runs} row for the team execution lands in FAILED with the
 *         exception message prefixed by {@code "Error: "}.</li>
 *     <li>No CONTENT_DELTA, no STOP — those only emit on the success path.</li>
 *   </ul>
 *
 *   <p>Why this canary matters: a refactor that swaps {@code sink.error(e)} for an
 *   {@code sink.next(new AgentStreamEvent(EventType.ERROR, ...))} silently changes the
 *   SSE wire contract — clients consuming the stream as a Flux see "completed
 *   successfully with an ERROR event" instead of "stream failed with terminal error".
 *   The two are observably different in the UI (toast vs. error boundary).
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class OrchestratorStreamingErrorRuntimeTest extends BaseIntegrationTest {

    private static final Duration STREAM_TIMEOUT = Duration.ofSeconds(15);

    @Autowired private AgentService agentService;
    @Autowired private AgentRepository agentRepository;
    @Autowired private FakeChatModel fakeChatModel;

    @BeforeEach
    void resetHarness() {
        fakeChatModel.reset();
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    @Test
    void sequentialTeamStream_memberThrows_emitsStartAndToolStart_thenFluxTerminatesWithError() {
        String m1 = persistMember("stream-err-seq-m1-" + UUID.randomUUID(), "Stage 1");
        String m2 = persistMember("stream-err-seq-m2-" + UUID.randomUUID(), "Stage 2");
        String rootId = persistTeam("stream-err-seq-root-" + UUID.randomUUID(), "Error Sequential",
                "SEQUENTIAL", List.of(m1, m2));

        // First member's ChatClient call throws via the FakeChatModel's lambda overload.
        // SequentialOrchestrator runs members in order, so member 2 is never reached.
        fakeChatModel.respondWith(prompt -> {
            throw new RuntimeException("boom — simulated member-1 LLM failure");
        });

        StreamCapture capture = collectStreamWithError(rootId, "stream error kickoff");

        assertAll("error-path streaming contract for SEQUENTIAL",
                () -> assertNotNull(capture.error,
                        "Flux must terminate with a reactive error — null here means the "
                                + "exception was silently swallowed in the boundedElastic worker"),
                () -> assertTrue(capture.events.size() >= 2,
                        "at least START + TOOL_START must be observed before the error — "
                                + "those events fire synchronously inside Flux.create and never "
                                + "depend on the worker completing; got " + capture.events.size()),
                () -> assertEquals(EventType.START, capture.events.get(0).event(),
                        "first event must still be START"),
                () -> assertEquals(EventType.TOOL_START, capture.events.get(1).event(),
                        "second event must still be TOOL_START — the error doesn't suppress "
                                + "the pre-dispatch announcement"),
                () -> assertTrue(capture.events.stream().noneMatch(e -> e.event() == EventType.STOP),
                        "STOP must NOT be emitted on the error path — only sink.complete() emits "
                                + "it, and the error path takes sink.error(e). A STOP event here "
                                + "means a refactor flipped error-as-event semantics (breaks "
                                + "client-side error boundaries)"),
                () -> assertTrue(capture.events.stream().noneMatch(e -> e.event() == EventType.CONTENT_DELTA),
                        "CONTENT_DELTA must NOT be emitted on the error path — it only fires "
                                + "after strategy.execute returns successfully"),
                () -> assertTrue(capture.error.getMessage() == null
                                || capture.error.getMessage().contains("boom")
                                || capture.error.getMessage().toLowerCase().contains("failure"),
                        "the propagated error must carry the original exception message for triage; "
                                + "got '" + capture.error.getMessage() + "'"));

        // Persistence side-effect: the team's agent_runs row must be FAILED with the prefixed
        // error message. AgentRunFinalizer is the single owner of that terminal write.
        Integer failedCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_runs WHERE agent_id = ? AND status = 'FAILED'",
                Integer.class, rootId);
        assertEquals(1, failedCount,
                "exactly one agent_runs row for the team execution must land in FAILED — "
                        + "0 here means the catch block didn't call finalizeRun (orphaned RUNNING row "
                        + "that the cancelOrphanedRunningAgentRuns sweeper would have to clean up "
                        + "hours later); >1 means duplicate inserts");
        String failedOutput = jdbc.queryForObject(
                "SELECT output FROM agent_runs WHERE agent_id = ? AND status = 'FAILED'",
                String.class, rootId);
        assertNotNull(failedOutput,
                "FAILED row's output column must be populated by AgentRunFinalizer");
        assertTrue(failedOutput.startsWith("Error: "),
                "output must be prefixed with 'Error: ' per the executeStream catch block — "
                        + "got '" + failedOutput + "'");
    }

    @Test
    void swarmTeamStream_decomposerThrows_emitsStartAndToolStart_thenFluxTerminatesWithError() {
        String mA = persistMember("stream-err-s-a-" + UUID.randomUUID(), "Worker A");
        String mB = persistMember("stream-err-s-b-" + UUID.randomUUID(), "Worker B");
        String rootId = persistTeam("stream-err-s-root-" + UUID.randomUUID(), "Error Swarm",
                "SWARM", List.of(mA, mB));

        // Swarm's decomposer is the first ChatModel call — make it throw. The fan-out
        // workers never get scheduled, so we test the strategy-level early-failure path
        // distinct from the per-worker partial-failure case already covered by
        // OrchestratorFailureSemanticsRuntimeTest.
        fakeChatModel.respondWith(prompt -> {
            throw new RuntimeException("boom — simulated swarm decomposer failure");
        });

        StreamCapture capture = collectStreamWithError(rootId, "swarm stream error kickoff");

        assertAll("error-path streaming contract for SWARM (decomposer failure)",
                () -> assertNotNull(capture.error,
                        "decomposer failure must propagate as a Flux error"),
                () -> assertEquals(EventType.START, capture.events.get(0).event()),
                () -> assertEquals(EventType.TOOL_START, capture.events.get(1).event()),
                () -> assertTrue(capture.events.size() == 2,
                        "exactly 2 pre-dispatch events expected; CONTENT_DELTA / STOP would "
                                + "indicate the decomposer didn't actually throw; got "
                                + capture.events.size()));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private record StreamCapture(List<AgentStreamEvent> events, Throwable error) {}

    /**
     * Subscribes to the team stream and captures BOTH the emitted events AND the
     * terminating error (if any). Using collectList().block() instead would lose the
     * pre-error events because Flux.toIterable() / collectList() throw the error
     * without returning the buffered prefix. We need both signals to assert the
     * contract.
     */
    private StreamCapture collectStreamWithError(String rootId, String input) {
        Flux<AgentStreamEvent> flux = agentService.stream(
                rootId, input, null, "stream-err-sess-" + UUID.randomUUID(),
                "stream-err-user", TenantConstants.DEFAULT_SYSTEM_ORG, Boolean.FALSE, null);

        List<AgentStreamEvent> events = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        flux.doOnNext(events::add)
                .doOnError(errorRef::set)
                .onErrorComplete()
                .blockLast(STREAM_TIMEOUT);

        return new StreamCapture(events, errorRef.get());
    }

    private String persistMember(String id, String name) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setOrgId(TenantConstants.DEFAULT_SYSTEM_ORG);
        a.setName(name);
        a.setDescription("Streaming-error fixture: " + name);
        a.setInstructions("Streaming-error fixture instructions");
        a.setModelId("gpt-4o-mini");
        a.setActive(true);
        a.setMaintenanceMode(false);
        a.setTeam(false);
        return agentRepository.save(a).getId();
    }

    private String persistTeam(String id, String name, String teamMode, List<String> members) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setOrgId(TenantConstants.DEFAULT_SYSTEM_ORG);
        a.setName(name);
        a.setDescription("Streaming-error team fixture: " + name);
        a.setInstructions("Streaming-error fixture instructions");
        a.setModelId("gpt-4o-mini");
        a.setActive(true);
        a.setMaintenanceMode(false);
        a.setTeam(true);
        a.setTeamMode(teamMode);
        a.setMembers(members);
        return agentRepository.save(a).getId();
    }
}
