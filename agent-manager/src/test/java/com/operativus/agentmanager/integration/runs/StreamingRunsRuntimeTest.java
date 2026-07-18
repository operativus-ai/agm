package com.operativus.agentmanager.integration.runs;

import com.operativus.agentmanager.core.model.EventType;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModel;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import com.operativus.agentmanager.integration.support.SseTestClient;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Black-box runtime coverage of the SSE streaming agent-run surface —
 *   {@code POST /api/agents/{agentId}/runs/stream} (handled by
 *   {@link com.operativus.agentmanager.control.controller.AgentsController#stream}
 *   → {@link com.operativus.agentmanager.core.registry.AgentOperations#stream}
 *   → {@link com.operativus.agentmanager.compute.service.AgentService#stream}
 *   → {@link com.operativus.agentmanager.compute.service.AgentStreamManager#stream}).
 *   Pins the canonical streaming path: the Flux event-order contract
 *   (START → CONTENT_DELTA×N → CONTENT_DONE → [FOLLOWUP_SUGGESTION] → STOP), the
 *   run/session/message write-through performed in {@code doOnComplete}, session affinity
 *   across two streaming turns, the generateFollowups=true event insertion, and the
 *   ResourceNotFoundException → 404 failure mode on unknown agentIds.
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 *
 * Cases mapped to {@code docs/testing/agm-runtime-testing.md} §6.4 and mirror the sync
 * coverage in {@link SyncRunsRuntimeTest} (happy path, session affinity, followups on/off,
 * 404) so streaming and non-streaming drift out of sync are caught side-by-side.
 *
 * Implementation notes / gaps these tests pin:
 *   - {@link com.operativus.agentmanager.control.controller.AgentsController#stream} returns
 *     {@code Flux<AgentStreamEvent>} with {@code MediaType.TEXT_EVENT_STREAM_VALUE}, NOT
 *     {@code Flux<ServerSentEvent<?>>}. Spring therefore serializes each element as JSON
 *     into a single {@code data:{json}} line per SSE frame — no {@code event:} discriminator
 *     line is emitted on the wire. The event-type check must read the {@code "event"}
 *     field from the parsed JSON payload; a reader that hands you {@code ServerSentEvent.event()}
 *     will always return null for this endpoint. The JSON payload also carries
 *     {@code "data"} (the AgentStreamEvent.data field) and {@code "timestamp"}.
 *   - {@link FakeChatModel#stream(org.springframework.ai.chat.prompt.Prompt)} emits one
 *     {@code ChatResponse} per scripted chunk; all intermediate chunks carry {@code null}
 *     finish-reason and the final chunk carries {@code "STOP"}. {@link com.operativus.agentmanager.compute.service.AgentStreamManager}
 *     converts each non-empty chunk text into a single {@code CONTENT_DELTA} event, so
 *     asserting on the CONTENT_DELTA count is equivalent to asserting on the scripted
 *     chunk count. No {@code REASONING_DELTA} events are emitted by the fake (no reasoning
 *     metadata in ChatGenerationMetadata).
 *   - The {@code doOnComplete} block on the STOP leg finalizes the run row (RUNNING →
 *     COMPLETED), writes {@code runRecord.output = fullResponse}, and invokes
 *     {@link com.operativus.agentmanager.compute.service.ReflectionService#reflectOnRun}.
 *     Because that callback runs on the reactive thread AFTER the final SSE frame has been
 *     flushed to the wire, tests MUST poll with Awaitility before asserting on
 *     {@code agent_runs.status} or {@code agent_messages} row counts — a plain
 *     {@code queryForMap} immediately after {@link SseTestClient#post} races with the
 *     doOnComplete callback and yields either {@code RUNNING} or a missing ASSISTANT row.
 *   - The Spring AI {@code MessageChatMemoryAdvisor} writes exactly one USER + one
 *     ASSISTANT row per stream (same contract as the sync path). Streaming does NOT
 *     subdivide the ASSISTANT row per chunk — the advisor concatenates content-deltas back
 *     to a single AssistantMessage before persisting. The 2-rows-per-turn invariant holds
 *     identically to sync runs, so a regression here fails alongside
 *     {@link SyncRunsRuntimeTest}'s happy-path pin.
 *   - {@code generateFollowups=true} on the streaming path issues ONE additional
 *     synchronous {@code client.prompt().call()} (Spring AI blocking API) from inside the
 *     followups Flux.defer (AgentStreamManager line 142-147). That call consumes a
 *     {@code FakeChatModel.respondWith} script entry — so the tested case must queue TWO
 *     scripts: one stream for the primary response, one sync call for the followups synth.
 *     The emitted {@code FOLLOWUP_SUGGESTION} event carries the raw synth response text so
 *     clients can parse the JSON-array string themselves.
 *   - Unknown {@code agentId} on the streaming endpoint flows through
 *     {@code AgentService.stream} line 444's {@code Flux.error(new ResourceNotFoundException)}
 *     and the {@link com.operativus.agentmanager.control.exception.GlobalExceptionHandler}
 *     maps that to a 404 RFC-7807 body. Because the error is a Mono/Flux terminal signal
 *     BEFORE any SSE frame is written, the HTTP status is 404 and the body is JSON, not
 *     {@code text/event-stream}. {@link SseTestClient#postWithStatus} exposes the status
 *     code so this path can still be asserted even though no SSE frames are emitted.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class StreamingRunsRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    // SSE streams here are small and complete quickly, but the doOnComplete write-through
    // races with the HTTP close. 15s covers context-boot slack on a cold JVM; 5s on the
    // DB poll fails fast when a write genuinely wedges.
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

    private void seedModel(String modelId) {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES (?, ?, 'fake', ?, true, false, true, 'CHAT', now())
                """, modelId, modelId, modelId);
    }

    // §6.4 — Canonical streaming run: script 3 stream chunks, assert the SSE event
    // sequence matches AgentStreamManager's Flux.concat order (START → CONTENT_DELTA×3 →
    // CONTENT_DONE → STOP), and assert the run/session/message persisted state matches
    // the sync-path contract (COMPLETED run, 1 session row, 2 agent_messages rows).
    // Splits into event-order + payload + persistence assertions so a regression in one
    // surface fails distinctly from a regression in another.
    @Test
    void streamingRunEmitsStartContentDeltaContentDoneStopAndPersistsRunSessionMessages() throws Exception {
        HttpHeaders auth = authenticatedHeaders("stream-runner");
        String agentId = createAgentViaApi(auth, "Streaming Happy Path Agent");
        String bearer = bearerFrom(auth);

        fakeModel.respondWithStream("Hello, ", "world", "!");

        String sessionId = "session-" + UUID.randomUUID();
        String body = json.writeValueAsString(Map.of(
                "message", "Please answer the streaming prompt.",
                "sessionId", sessionId));

        List<Map<String, Object>> frames = streamFrames(sse.post(
                "/api/agents/" + agentId + "/runs/stream", body, bearer, SSE_TIMEOUT));

        List<String> eventNames = frames.stream().map(f -> (String) f.get("event")).toList();
        assertEquals(
                List.of(EventType.START.name(), EventType.CONTENT_DELTA.name(),
                        EventType.CONTENT_DELTA.name(), EventType.CONTENT_DELTA.name(),
                        EventType.CONTENT_DONE.name(), EventType.STOP.name()),
                eventNames,
                "SSE event order must match AgentStreamManager.concat: START → N×CONTENT_DELTA → CONTENT_DONE → STOP; any reorder signals advisor or Flux-graph drift");

        List<String> contentDeltas = frames.stream()
                .filter(f -> EventType.CONTENT_DELTA.name().equals(f.get("event")))
                .map(f -> (String) f.get("data"))
                .toList();
        assertEquals(List.of("Hello, ", "world", "!"), contentDeltas,
                "each scripted stream chunk must surface as a distinct CONTENT_DELTA frame carrying its chunk text verbatim");

        // doOnComplete write-through is async to the SSE close — poll.
        Awaitility.await().atMost(AWAIT_TIMEOUT).untilAsserted(() -> {
            Map<String, Object> runRow = jdbc.queryForMap(
                    "SELECT status, output, session_id, agent_id FROM agent_runs WHERE session_id = ?",
                    sessionId);
            assertEquals("COMPLETED", runRow.get("status"),
                    "doOnComplete must set RunStatus.COMPLETED on the run record; RUNNING here means the callback did not fire before assertion");
            assertEquals("Hello, world!", runRow.get("output"),
                    "doOnComplete must persist the accumulated fullResponse (chunk concat) as run.output");
            assertEquals(agentId, runRow.get("agent_id"));
        });

        Long sessionCount = jdbc.queryForObject(
                "SELECT count(*) FROM agent_sessions WHERE session_id = ?", Long.class, sessionId);
        assertEquals(1L, sessionCount,
                "ensureSessionExists must create exactly one agent_sessions row on the streaming path — same contract as sync runs");

        Awaitility.await().atMost(AWAIT_TIMEOUT).untilAsserted(() -> {
            Long messageCount = jdbc.queryForObject(
                    "SELECT count(*) FROM agent_messages WHERE session_id = ?", Long.class, sessionId);
            assertEquals(2L, messageCount,
                    "streaming run must produce 1 USER + 1 ASSISTANT row via MessageChatMemoryAdvisor; chunked ASSISTANT splits would fail here");
        });

        Long userRows = jdbc.queryForObject(
                "SELECT count(*) FROM agent_messages WHERE session_id = ? AND message_type = 'USER'",
                Long.class, sessionId);
        Long assistantRows = jdbc.queryForObject(
                "SELECT count(*) FROM agent_messages WHERE session_id = ? AND message_type = 'ASSISTANT'",
                Long.class, sessionId);
        assertEquals(1L, userRows, "exactly one USER row per single-turn streaming prompt");
        assertEquals(1L, assistantRows, "exactly one ASSISTANT row per single-turn streaming prompt (advisor concatenates deltas)");
    }

    // Mirrors SyncRunsRuntimeTest.twoSyncRunsWithSameSessionIdAppendToOneSessionAndFourMessages
    // for the streaming surface. Two back-to-back streaming turns on the same sessionId
    // must persist to exactly one agent_sessions row, two agent_runs rows, and four
    // agent_messages rows. Protects against a regression where streaming accidentally
    // rotates the session per-call (sync and streaming share ensureSessionExists — this
    // is the pin that asserts they do on the streaming side).
    @Test
    void twoStreamingRunsWithSameSessionIdAppendToOneSessionAndFourMessages() throws Exception {
        HttpHeaders auth = authenticatedHeaders("stream-session-runner");
        String agentId = createAgentViaApi(auth, "Streaming Session Agent");
        String bearer = bearerFrom(auth);

        fakeModel.respondWithStream("First stream reply.")
                .respondWithStream("Second stream reply.");

        String sessionId = "session-" + UUID.randomUUID();
        String first = json.writeValueAsString(Map.of(
                "message", "First streaming turn.", "sessionId", sessionId));
        String second = json.writeValueAsString(Map.of(
                "message", "Second streaming turn.", "sessionId", sessionId));

        List<Map<String, Object>> firstFrames = streamFrames(sse.post(
                "/api/agents/" + agentId + "/runs/stream", first, bearer, SSE_TIMEOUT));
        assertTrue(firstFrames.stream().anyMatch(f -> EventType.STOP.name().equals(f.get("event"))),
                "first streaming turn must close with STOP before we issue the second — otherwise we're testing a concurrent race, not session affinity");

        List<Map<String, Object>> secondFrames = streamFrames(sse.post(
                "/api/agents/" + agentId + "/runs/stream", second, bearer, SSE_TIMEOUT));
        assertTrue(secondFrames.stream().anyMatch(f -> EventType.STOP.name().equals(f.get("event"))));

        // Both doOnComplete callbacks must land before the row-count assertions.
        Awaitility.await().atMost(AWAIT_TIMEOUT).untilAsserted(() -> {
            Long completedRuns = jdbc.queryForObject(
                    "SELECT count(*) FROM agent_runs WHERE session_id = ? AND status = 'COMPLETED'",
                    Long.class, sessionId);
            assertEquals(2L, completedRuns, "both streaming runs must land COMPLETED before we assert on message counts");
        });

        Long sessionRows = jdbc.queryForObject(
                "SELECT count(*) FROM agent_sessions WHERE session_id = ?", Long.class, sessionId);
        assertEquals(1L, sessionRows,
                "ensureSessionExists must dedupe on the second streaming turn — same row gets its updated_at bumped");

        Long runRows = jdbc.queryForObject(
                "SELECT count(*) FROM agent_runs WHERE session_id = ?", Long.class, sessionId);
        assertEquals(2L, runRows,
                "each streaming turn still gets its own agent_runs row — session is a grouping, not a collapser");

        Awaitility.await().atMost(AWAIT_TIMEOUT).untilAsserted(() -> {
            Long messageRows = jdbc.queryForObject(
                    "SELECT count(*) FROM agent_messages WHERE session_id = ?", Long.class, sessionId);
            assertEquals(4L, messageRows,
                    "two streaming turns × (1 USER + 1 ASSISTANT) = 4; fewer here means the advisor dropped history across streams");
        });
    }

    // §6.4 + followups plumbing — generateFollowups=true on the streaming endpoint must
    // emit a FOLLOWUP_SUGGESTION event AFTER CONTENT_DONE and BEFORE STOP, carrying the
    // raw synth response text. Pins both (a) position in the event sequence and (b) data
    // payload round-trip, so a change to AgentStreamManager's Flux.concat order would
    // fail distinctly from a change to the synth-call wiring.
    @Test
    void streamingRunWithGenerateFollowupsTrueEmitsFollowupSuggestionBetweenContentDoneAndStop() throws Exception {
        HttpHeaders auth = authenticatedHeaders("stream-followups-runner");
        String agentId = createAgentViaApi(auth, "Streaming Followups Agent");
        String bearer = bearerFrom(auth);

        // Script: 2 stream chunks for primary, 1 sync response for followups synth.
        fakeModel.respondWithStream("Primary ", "streamed answer.")
                .respondWith("[\"What next?\", \"Can you expand?\", \"Any edge cases?\"]");

        String sessionId = "session-" + UUID.randomUUID();
        String body = json.writeValueAsString(Map.of(
                "message", "Stream with followups on.",
                "sessionId", sessionId,
                "generateFollowups", true));

        List<Map<String, Object>> frames = streamFrames(sse.post(
                "/api/agents/" + agentId + "/runs/stream", body, bearer, SSE_TIMEOUT));

        List<String> eventNames = frames.stream().map(f -> (String) f.get("event")).toList();
        assertEquals(
                List.of(EventType.START.name(),
                        EventType.CONTENT_DELTA.name(), EventType.CONTENT_DELTA.name(),
                        EventType.CONTENT_DONE.name(),
                        EventType.FOLLOWUP_SUGGESTION.name(),
                        EventType.STOP.name()),
                eventNames,
                "FOLLOWUP_SUGGESTION must appear AFTER CONTENT_DONE and BEFORE STOP — clients rely on CONTENT_DONE to render the final answer, then FOLLOWUP_SUGGESTION to render the chips");

        Map<String, Object> followup = frames.stream()
                .filter(f -> EventType.FOLLOWUP_SUGGESTION.name().equals(f.get("event")))
                .findFirst().orElseThrow();
        assertEquals("[\"What next?\", \"Can you expand?\", \"Any edge cases?\"]",
                followup.get("data"),
                "FOLLOWUP_SUGGESTION.data must be the raw synth response verbatim so clients can parse the JSON array themselves");

        // The stream() (1 upstream call) + synth call() (1 upstream call) = 2 prompts.
        // NoOpReflectionServiceConfig suppresses the otherwise-racing third prompt.
        assertEquals(2, fakeModel.receivedPrompts().size(),
                "generateFollowups=true on the streaming path must issue exactly 2 upstream prompts — one stream(), one sync call() for the synth");
    }

    // Negative of the prior case: generateFollowups absent (controller coerces null → false
    // at AgentsController.stream line 137) must NOT emit a FOLLOWUP_SUGGESTION event and
    // must NOT issue a second upstream call. Catches a default-flip regression (null → true)
    // that would silently double LLM cost per streaming run.
    @Test
    void streamingRunWithoutGenerateFollowupsOmitsFollowupSuggestionAndIssuesOnlyOneUpstreamCall() throws Exception {
        HttpHeaders auth = authenticatedHeaders("stream-no-followups-runner");
        String agentId = createAgentViaApi(auth, "Streaming No-Followups Agent");
        String bearer = bearerFrom(auth);

        fakeModel.respondWithStream("Primary-only ", "streamed answer.");

        String sessionId = "session-" + UUID.randomUUID();
        // generateFollowups deliberately omitted — exercises the null-coercion branch.
        String body = json.writeValueAsString(Map.of(
                "message", "Stream with followups omitted.",
                "sessionId", sessionId));

        List<Map<String, Object>> frames = streamFrames(sse.post(
                "/api/agents/" + agentId + "/runs/stream", body, bearer, SSE_TIMEOUT));

        List<String> eventNames = frames.stream().map(f -> (String) f.get("event")).toList();
        assertFalse(eventNames.contains(EventType.FOLLOWUP_SUGGESTION.name()),
                "generateFollowups absent/false must NOT emit a FOLLOWUP_SUGGESTION event — prevents a default-flip from silently doubling LLM cost per run");
        assertEquals(
                List.of(EventType.START.name(),
                        EventType.CONTENT_DELTA.name(), EventType.CONTENT_DELTA.name(),
                        EventType.CONTENT_DONE.name(),
                        EventType.STOP.name()),
                eventNames,
                "event sequence with followups disabled must be START → N×CONTENT_DELTA → CONTENT_DONE → STOP (no FOLLOWUP_SUGGESTION leg)");

        assertEquals(1, fakeModel.receivedPrompts().size(),
                "followups disabled must issue exactly ONE upstream prompt — the primary stream() call");
    }

    // §6.3 streaming flavor — unknown agentId on the streaming endpoint must surface as
    // HTTP 404 (ResourceNotFoundException → RFC-7807 JSON body) instead of an empty SSE
    // stream or a 500. Because the failure fires BEFORE any SSE frame is written, the
    // response body is JSON, not text/event-stream — SseTestClient.post alone would
    // collapse this into an empty event list and lose the status code, so this test uses
    // postWithStatus (see SseTestClient:73-89).
    @Test
    void streamingRunAgainstUnknownAgentIdReturns404AndEmitsNoSseFrames() throws Exception {
        HttpHeaders auth = authenticatedHeaders("stream-404-caller");
        String bearer = bearerFrom(auth);

        String unknownAgentId = "agent-does-not-exist-" + UUID.randomUUID();
        String body = json.writeValueAsString(Map.of(
                "message", "This should never reach a ChatModel stream.",
                "sessionId", "session-" + UUID.randomUUID()));

        SseTestClient.SseResponse response = sse.postWithStatus(
                "/api/agents/" + unknownAgentId + "/runs/stream", body, bearer, SSE_TIMEOUT);

        assertEquals(HttpStatus.NOT_FOUND.value(), response.statusCode(),
                "unknown agentId on the streaming endpoint must surface as 404 (ResourceNotFoundException → RFC-7807), not 500 or 200-with-empty-stream");

        Long runRows = jdbc.queryForObject(
                "SELECT count(*) FROM agent_runs WHERE agent_id = ?", Long.class, unknownAgentId);
        assertEquals(0L, runRows,
                "404 path must short-circuit before any runRepository.save — no orphan RUNNING rows for an invalid agentId");

        assertTrue(fakeModel.receivedPrompts().isEmpty(),
                "404 path must not reach the FakeChatModel boundary — otherwise we're paying LLM cost for an invalid streaming request");
    }

    // ─── helpers ───

    /**
     * Parses each SSE frame's {@code data:} line (which carries the JSON-serialized
     * AgentStreamEvent, because AgentsController returns {@code Flux<AgentStreamEvent>}
     * rather than {@code Flux<ServerSentEvent<?>>}) into a {@code Map} so assertions can
     * read {@code event}/{@code data}/{@code timestamp} the same way they would on a
     * deserialized AgentStreamEvent record. Using Map here keeps the helper independent
     * of whether the production record's field set changes over time.
     */
    private List<Map<String, Object>> streamFrames(List<ServerSentEvent<String>> raw) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>(raw.size());
        for (ServerSentEvent<String> e : raw) {
            if (e.data() == null || e.data().isBlank()) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = json.readValue(e.data(), Map.class);
            out.add(parsed);
        }
        return out;
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
        body.put("description", "Streaming run test owner");
        body.put("instructions", "Be helpful.");
        body.put("model", "gpt-4o-mini");
        body.put("isReasoningEnabled", false);
        body.put("isTeam", false);
        body.put("requiresPiiRedaction", false);
        body.put("approvedForProduction", false);
        body.put("maintenanceMode", false);
        body.put("active", true);
        body.put("enforceJsonOutput", false);
        // Same gotcha pinned in SyncRunsRuntimeTest.createAgentViaApi: AgentEntity ctor
        // coerces null memoryEnabled → false (AgentEntity.java:212), which suppresses the
        // MessageChatMemoryAdvisor wiring and collapses agent_messages row count to 0.
        // Streaming asserts on the same advisor plumbing, so opt in explicitly.
        body.put("memoryEnabled", true);
        body.put("addHistoryToMessages", true);

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/admin/agents"), HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(),
                "fixture precondition: agent must exist before stream endpoints reference it");
        return agentId;
    }

    private HttpHeaders authenticatedHeaders(String username) {
        return authenticateAs(username, username + "@test.local", "pass-stream-1234", List.of("ROLE_USER", "ROLE_ADMIN"));
    }
}
