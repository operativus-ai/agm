package ai.operativus.agentmanager.integration.runs;

import ai.operativus.agentmanager.core.model.EventType;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModel;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import ai.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import ai.operativus.agentmanager.integration.support.SseTestClient;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins the SSE event sequence and persistence contract when the
 *   upstream chat model throws mid-stream. Complements
 *   {@link ai.operativus.agentmanager.integration.runs.BackgroundRunFailurePathRuntimeTest}
 *   (failure path for background runs) and
 *   {@link ai.operativus.agentmanager.integration.teams.OrchestratorStreamingErrorRuntimeTest}
 *   (orchestrator-side streaming errors) — neither covers the leaf-agent
 *   {@code POST /api/agents/{agentId}/runs/stream} surface.
 *
 *   <p><strong>Production-code contract</strong> (see
 *   {@code AgentStreamManager.stream} lines 102-158): when the upstream {@code ChatClient}
 *   stream emits {@link Flux#error(Throwable)}, the {@code onErrorResume} block:
 *   <ol>
 *     <li>Logs the exception.</li>
 *     <li>Calls {@code agentRunFinalizer.finalizeRun(runId, FAILED, "Error: ...", ...)}
 *         — the {@code agent_runs} row transitions to {@code FAILED}.</li>
 *     <li>Returns a single-element {@code Flux.just(ERROR-event)} with payload
 *         {@code "Stream Error: " + errorDetail} (generic branch) or a friendlier
 *         classified message (context-limit / rate-limit / quota branches).</li>
 *   </ol>
 *   The outer {@code Flux.concat(start, content, contentDone, followups, stop)} keeps
 *   flowing, so the wire sees {@code START → ERROR → CONTENT_DONE → STOP}. The
 *   {@code stop} leg's {@code doOnComplete} guard ({@code if status != FAILED}) skips
 *   the COMPLETED rewrite, preserving the FAILED row.
 *
 *   <p>Three pins below:
 *   <ol>
 *     <li>Generic upstream exception mid-stream → ERROR frame carrying the classname/
 *         message in {@code data}.</li>
 *     <li>Event sequence is {@code START → ERROR → CONTENT_DONE → STOP} (NOT
 *         {@code START → CONTENT_DELTA × N → ERROR → ...}) — the
 *         {@code onErrorResume} swallows any pre-error delta state and the failure
 *         lands as a single ERROR frame at the position the upstream stream broke.</li>
 *     <li>Row status lands on {@code FAILED} with output prefixed {@code "Error: "} —
 *         the finalizer's payload-shape contract.</li>
 *   </ol>
 *
 * State: Stateless. Inherits Testcontainers Postgres + full app context from
 *   {@link BaseIntegrationTest}.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class StreamingRunErrorFrameRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    private static final Duration SSE_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(5);

    @Autowired
    private FakeChatModel fakeModel;

    private SseTestClient sse;

    @BeforeEach
    void resetStateBeforeTest() {
        truncateDatabase();
        seedModel("gpt-4o-mini");
        fakeModel.reset();
        sse = new SseTestClient("http://localhost:" + port);
    }

    // Pin: upstream Flux.error mid-stream surfaces as a single ERROR event on the SSE
    // wire. AgentStreamManager.stream's onErrorResume swallows the throwable and emits
    // a synthesized AgentStreamEvent(ERROR, ...) — clients see graceful degradation
    // instead of a broken connection or an HTTP 500 mid-body.
    @Test
    void chatModelThrowsImmediately_streamEmitsErrorFrameAndPersistsFailedStatus() throws Exception {
        HttpHeaders auth = authenticatedHeaders("stream-error-runner");
        String agentId = createAgentViaApi(auth, "Streaming Error Frame Agent");
        String bearer = bearerFrom(auth);

        // The upstream stream errors before emitting any content. We deliberately use a
        // recognizable exception class so the test can assert the payload carries it
        // verbatim — this is the contract the FE consumes via error-toast renderers.
        RuntimeException upstreamFailure = new IllegalStateException(
                "Synthetic upstream failure: model timed out");
        fakeModel.respondWithStreamFunction(p -> Flux.error(upstreamFailure));

        String sessionId = "session-stream-error-" + UUID.randomUUID();
        String body = json.writeValueAsString(Map.of(
                "message", "Stream that will fail upstream.",
                "sessionId", sessionId));

        List<ServerSentEvent<String>> rawFrames = sse.post(
                "/api/agents/" + agentId + "/runs/stream", body, bearer, SSE_TIMEOUT);
        assertNotNull(rawFrames, "SSE response must materialize within the timeout");

        List<Map<String, Object>> frames = parseFrames(rawFrames);
        List<String> eventNames = frames.stream().map(f -> (String) f.get("event")).toList();

        assertEquals(
                List.of(EventType.START.name(), EventType.ERROR.name(),
                        EventType.CONTENT_DONE.name(), EventType.STOP.name()),
                eventNames,
                "upstream stream error must surface as START → ERROR → CONTENT_DONE → STOP. "
                        + "A CONTENT_DELTA in here would mean the error leaked partial content; "
                        + "a missing STOP means doFinally didn't fire and the connection wedged.");

        Map<String, Object> errorFrame = frames.stream()
                .filter(f -> EventType.ERROR.name().equals(f.get("event")))
                .findFirst().orElseThrow();
        String errorPayload = (String) errorFrame.get("data");
        assertTrue(errorPayload != null && errorPayload.startsWith("Stream Error: "),
                "ERROR.data must use the generic 'Stream Error: ' prefix (line 157 of "
                        + "AgentStreamManager.stream); a different prefix means error-classification "
                        + "diverged from the contract or a new classification branch was added "
                        + "without test coverage. Actual: " + errorPayload);
        assertTrue(errorPayload.contains(IllegalStateException.class.getName()),
                "ERROR.data must include the upstream exception classname so the FE can route "
                        + "on it for retry semantics. Actual: " + errorPayload);
        assertTrue(errorPayload.contains("Synthetic upstream failure: model timed out"),
                "ERROR.data must round-trip the upstream message verbatim so the FE error "
                        + "toast carries debuggable text. Actual: " + errorPayload);

        // Row finalization runs in onErrorResume (synchronous to the Flux subscription)
        // so it's typically committed before the SSE close, but doOnComplete on the
        // STOP leg races — poll instead of asserting immediately.
        Awaitility.await().atMost(AWAIT_TIMEOUT).untilAsserted(() -> {
            Map<String, Object> runRow = jdbc.queryForMap(
                    "SELECT status, output FROM agent_runs WHERE session_id = ?", sessionId);
            assertEquals("FAILED", runRow.get("status"),
                    "agent_runs.status must be FAILED after upstream stream error — "
                            + "doOnComplete's 'status != FAILED' guard prevents the COMPLETED "
                            + "rewrite; if you see COMPLETED here, that guard regressed.");
            String output = (String) runRow.get("output");
            assertTrue(output != null && output.startsWith("Error: "),
                    "agent_runs.output must carry the 'Error: ' prefix (AgentStreamManager line 132). "
                            + "Actual: " + output);
        });
    }

    // Pin AS-IS: even when the upstream Flux emits chunks BEFORE erroring, those chunks
    // are discarded by ContentSafetyAdvisor.adviseStream (which uses collectList →
    // flatMapMany per the CLAUDE.md "Streaming output safety is tier-keyed" note). The
    // entire output collapses to block-render in exchange for the safety gate, so on
    // error the wire sequence is identical to the immediate-error case — START → ERROR
    // → CONTENT_DONE → STOP — regardless of how many chunks "would have" emitted.
    //
    // This is the documented safety-vs-UX tradeoff: TIER_1_STANDARD streaming output
    // passes through unredacted ONLY on success; any error path collapses to no-content.
    // A regression here (CONTENT_DELTA appearing before ERROR) would mean
    // ContentSafetyAdvisor.adviseStream stopped using collectList — the safety gate
    // would have moved, and a separate review is warranted.
    @Test
    void chatModelEmitsTwoChunksThenThrows_streamingSafetyGateDiscardsPriorContentBeforeErrorFrame() throws Exception {
        HttpHeaders auth = authenticatedHeaders("stream-error-mid-runner");
        String agentId = createAgentViaApi(auth, "Streaming Mid-Flight Error Agent");
        String bearer = bearerFrom(auth);

        ChatResponse chunk1 = textResponse("Partial answer chunk one. ");
        ChatResponse chunk2 = textResponse("Partial chunk two before failure. ");
        RuntimeException mid = new RuntimeException("Synthetic mid-stream failure after 2 chunks");
        fakeModel.respondWithStreamFunction(p ->
                Flux.<ChatResponse>concat(Flux.just(chunk1, chunk2), Flux.error(mid)));

        String sessionId = "session-mid-stream-error-" + UUID.randomUUID();
        String body = json.writeValueAsString(Map.of(
                "message", "Stream that will fail after a couple chunks.",
                "sessionId", sessionId));

        List<Map<String, Object>> frames = parseFrames(sse.post(
                "/api/agents/" + agentId + "/runs/stream", body, bearer, SSE_TIMEOUT));
        List<String> eventNames = frames.stream().map(f -> (String) f.get("event")).toList();

        assertEquals(
                List.of(EventType.START.name(), EventType.ERROR.name(),
                        EventType.CONTENT_DONE.name(), EventType.STOP.name()),
                eventNames,
                "AS-IS pin: chunks emitted before the upstream error are discarded by the "
                        + "ContentSafetyAdvisor.adviseStream collectList gate. The wire sequence "
                        + "collapses to START → ERROR → CONTENT_DONE → STOP — identical to the "
                        + "immediate-error case. A CONTENT_DELTA appearing here would mean the "
                        + "safety gate moved — review ContentSafetyAdvisor before relaxing.");

        Map<String, Object> errorFrame = frames.stream()
                .filter(f -> EventType.ERROR.name().equals(f.get("event")))
                .findFirst().orElseThrow();
        assertTrue(((String) errorFrame.get("data")).contains("Synthetic mid-stream failure after 2 chunks"),
                "ERROR.data must carry the upstream message even when prior chunks were buffered then discarded");

        Awaitility.await().atMost(AWAIT_TIMEOUT).untilAsserted(() -> {
            String status = jdbc.queryForObject(
                    "SELECT status FROM agent_runs WHERE session_id = ?", String.class, sessionId);
            assertEquals("FAILED", status,
                    "row must be FAILED regardless of where in the stream the upstream failure landed");
        });
    }

    // ─── helpers ───

    private List<Map<String, Object>> parseFrames(List<ServerSentEvent<String>> raw) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>(raw.size());
        for (ServerSentEvent<String> e : raw) {
            if (e.data() == null || e.data().isBlank()) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = json.readValue(e.data(), Map.class);
            out.add(parsed);
        }
        return out;
    }

    private static ChatResponse textResponse(String text) {
        ChatGenerationMetadata meta = ChatGenerationMetadata.NULL;
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text), meta)));
    }

    private void seedModel(String modelId) {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES (?, ?, 'fake', ?, true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """, modelId, modelId, modelId);
    }

    private String bearerFrom(HttpHeaders auth) {
        String header = auth.getFirst("Authorization");
        return header == null ? "" : header.substring("Bearer ".length());
    }

    private String createAgentViaApi(HttpHeaders auth, String name) {
        String agentId = "agent-" + UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId);
        body.put("name", name);
        body.put("description", "Streaming error frame fixture");
        body.put("instructions", "Be helpful.");
        body.put("model", "gpt-4o-mini");
        body.put("isReasoningEnabled", false);
        body.put("isTeam", false);
        body.put("requiresPiiRedaction", false);
        body.put("approvedForProduction", false);
        body.put("maintenanceMode", false);
        body.put("active", true);
        body.put("enforceJsonOutput", false);
        body.put("memoryEnabled", true);
        body.put("addHistoryToMessages", true);

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/admin/agents"), HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(),
                "fixture precondition: agent must exist before stream endpoints reference it");
        return agentId;
    }

    private HttpHeaders authenticatedHeaders(String username) {
        return authenticateAs(username, username + "@test.local", "pass-stream-error-1234",
                // ROLE_ADMIN required to create the fixture agent via /api/admin/agents (gated since #969).
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }
}
