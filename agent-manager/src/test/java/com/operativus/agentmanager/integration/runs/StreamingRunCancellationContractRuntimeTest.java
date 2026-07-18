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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins the contract observed when {@code DELETE /api/agents/{agentId}/runs/{runId}}
 *   is issued against an in-flight SSE streaming run. Closes a gap between
 *   {@link RunCancellationIdempotencyRuntimeTest} (idempotency on already-terminal runs)
 *   and {@link StreamingRunsRuntimeTest} (canonical event-order pin) — neither covers the
 *   live-cancel handoff between the controller's {@code Flux<AgentStreamEvent>} and
 *   {@code AgentOperations.cancelRun} → {@code RunExecutionManager.cancel}.
 *
 *   <p>The asymmetry pinned here matters for the FE chat path: {@code chat-api.ts}
 *   exposes a {@code cancelRun()} action wired to the Stop button on the streaming
 *   {@code MessageBubble}. Whether that action terminates the in-flight SSE Flux or only
 *   marks the run row is a product-visible contract — without this pin, a refactor of
 *   {@code RunExecutionManager.cancel} could regress either side silently.
 *
 *   <p><strong>Pre-pin reality observed in production code:</strong>
 *   <ol>
 *     <li>The streaming surface ({@code AgentsController.stream}) returns a
 *         {@code Flux<AgentStreamEvent>} subscribed by Spring WebFlux on the request
 *         I/O scheduler — NOT a virtual thread tracked by {@code RunExecutionManager.activeRuns}.</li>
 *     <li>{@code RunExecutionManager.cancel(runId)} (line 277): first branch attempts
 *         {@code Future.cancel(true)} on the {@code activeRuns} map; second branch falls
 *         through to {@code agentRunFinalizer.finalizeRun(..., CANCELLED, ...)} when the
 *         run row isn't terminal. The first branch is a no-op for streaming runs (they
 *         never register in the map); the second branch only updates the row.</li>
 *     <li>Consequence: cancelling a streaming run in flight transitions the
 *         {@code agent_runs} row to {@code CANCELLED} but does NOT unsubscribe the Flux —
 *         the SSE stream continues to emit content-delta frames and a terminal STOP
 *         frame after the row is already CANCELLED. The doOnComplete write-through then
 *         tries to flip the row to COMPLETED, but the finalizer's already-terminal guard
 *         (see {@code RunCancellationIdempotencyRuntimeTest} §1-2) short-circuits and
 *         the row stays CANCELLED.</li>
 *   </ol>
 *
 *   <p>This contract is documented AS-IS — the SSE-continues-after-cancel behavior is
 *   pinned, not endorsed. A follow-up PR that wires the cancellation signal into the
 *   Flux (via {@code Flux.takeUntilOther} on a cancellation signal sourced from
 *   {@code AgentContextHolder} or a per-runId sink in {@code RunExecutionManager})
 *   would flip the SSE assertion to expect a terminated stream.
 *
 * State: Stateless. Inherits Testcontainers Postgres + full app context from
 *   {@link BaseIntegrationTest}.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class StreamingRunCancellationContractRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    private static final Duration SSE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    private FakeChatModel fakeModel;

    private SseTestClient sse;

    @BeforeEach
    void resetStateBeforeTest() {
        truncateDatabase();
        seedModel("gpt-4o-mini");
        fakeModel.reset();
        sse = new SseTestClient("http://localhost:" + port);
        installPermissiveErrorHandler();
    }

    // Pin: a DELETE issued mid-stream returns 204 even though no per-runId tracking
    // for streaming runs exists in RunExecutionManager.activeRuns. The controller calls
    // agentOperations.cancelRun(runId) blind; the finalizer fallback transitions the
    // agent_runs row to CANCELLED without throwing — matching what the FE Stop button
    // expects to observe on the network panel.
    @Test
    void deleteDuringActiveSseStream_returns204_andTransitionsAgentRunRowToCancelled() throws Exception {
        HttpHeaders auth = authenticatedHeaders("cancel-stream-runner");
        String agentId = createAgentViaApi(auth, "Streaming Cancel Contract Agent");
        String bearer = bearerFrom(auth);

        // Slow stream: 4 chunks × 400ms = ~1.6s in flight. Plenty of time for the DELETE
        // to land while the Flux is still emitting.
        fakeModel.respondWithSlowStream(Duration.ofMillis(400),
                "First ", "second ", "third ", "fourth.");

        String sessionId = "session-cancel-stream-" + UUID.randomUUID();
        String body = json.writeValueAsString(Map.of(
                "message", "Stream a long answer so I can cancel it mid-flight.",
                "sessionId", sessionId));

        // Fire the streaming request on a background thread so the test thread can
        // poll for the runId + issue the DELETE while the SSE Flux is still emitting.
        CompletableFuture<List<ServerSentEvent<String>>> streamFuture = CompletableFuture.supplyAsync(() ->
                sse.post("/api/agents/" + agentId + "/runs/stream", body, bearer, SSE_TIMEOUT));

        // Poll for the agent_runs row to appear. The row is created BEFORE the first
        // chunk is emitted (AgentService.stream's run-record save runs synchronously on
        // the subscription thread), so a short Awaitility window is sufficient.
        String runId = pollForRunningRunId(sessionId);

        // Issue the DELETE while the slow stream is still emitting. With 400ms per chunk,
        // <1.6s have elapsed when the agent_runs row first appears; ample headroom for
        // the DELETE round-trip before the doOnComplete write-through fires.
        ResponseEntity<Void> deleteResponse = rest.exchange(
                url("/api/agents/" + agentId + "/runs/" + runId),
                HttpMethod.DELETE, new HttpEntity<>(auth), Void.class);
        assertEquals(HttpStatus.NO_CONTENT, deleteResponse.getStatusCode(),
                "DELETE on an in-flight streaming run must return 204. Any 4xx here would "
                        + "force the FE Stop button to special-case the streaming case before "
                        + "the SSE handler closes — breaks the chat-api.ts cancelRun() contract.");

        // Wait for the SSE stream to terminate either by cancellation or natural
        // completion. We accept either signal — the row-state assertion below pins the
        // observable contract; this just keeps the test deterministic.
        List<ServerSentEvent<String>> frames = streamFuture.get(SSE_TIMEOUT.toSeconds() + 5, TimeUnit.SECONDS);
        assertNotNull(frames, "SSE response must materialize within the configured timeout");
        assertTrue(frames.size() >= 1,
                "at least the START frame must have flushed before the DELETE landed — "
                        + "otherwise the test races the agent_runs row creation");

        // Pin AS-IS: the agent_runs row lands on CANCELLED, NOT COMPLETED. RunExecutionManager.cancel
        // → AgentRunFinalizer.finalizeRun writes CANCELLED first; the doOnComplete leg
        // then re-enters the finalizer but the already-terminal guard short-circuits.
        // A regression here (row landing on COMPLETED) means either (a) the cancel write
        // arrived AFTER doOnComplete — Awaitility window too tight, race in the test —
        // OR (b) the finalizer's already-terminal short-circuit regressed.
        Awaitility.await().atMost(AWAIT_TIMEOUT).untilAsserted(() -> {
            String status = jdbc.queryForObject(
                    "SELECT status FROM agent_runs WHERE id = ?", String.class, runId);
            assertEquals("CANCELLED", status,
                    "DELETE on a RUNNING streaming run must transition the row to CANCELLED "
                            + "(via RunExecutionManager.cancel's finalizer fallback). COMPLETED here "
                            + "means doOnComplete won the race — investigate the finalizer's "
                            + "already-terminal guard before relaxing this assertion.");
        });

        // Post-fix: cancelling a streaming run terminates the Flux via the per-runId
        // cancellation sink registered with RunExecutionManager. The SSE stream stops
        // emitting CONTENT_DELTA frames and the natural-completion STOP is suppressed.
        // The cancel signal is translated into a terminal CANCELLED AgentStreamEvent so
        // SSE clients can distinguish user-initiated cancellation from a network drop —
        // the FE chat-api.ts switches on event.event to drive UI state.
        List<String> eventNames = parseEventNames(frames);
        assertTrue(eventNames.contains(EventType.START.name()),
                "START frame must always be present — sanity check on SSE frame parsing");
        assertTrue(!eventNames.contains(EventType.STOP.name()),
                "post-fix: cancelled streams must NOT emit a terminal STOP frame — "
                        + "the cancellation sink terminates the merged Flux before the "
                        + "natural-completion STOP leg fires. A STOP here means the "
                        + "cancellation signal didn't propagate — check that "
                        + "RunExecutionManager.cancel emits on the sink before the activeRuns "
                        + "branch and that AgentStreamManager.stream registered the sink.");
        assertTrue(eventNames.contains(EventType.CANCELLED.name()),
                "post-fix: cancelled streams MUST emit a terminal CANCELLED frame so "
                        + "clients can distinguish user-initiated cancel from a network drop. "
                        + "Pre-fix the stream closed silently after takeUntilOther — clients "
                        + "saw only the START frame and had to infer the cause from the "
                        + "connection close. A missing CANCELLED here means either (a) the "
                        + "cancel-terminal Flux didn't merge into the main stream or (b) "
                        + "takeUntil(ev → ev == CANCELLED) regressed.");
    }

    // Pin: cancelling a streaming run twice (DELETE × 2) is idempotent on the streaming
    // path the same way it is on the sync/background path (RunCancellationIdempotencyRuntimeTest).
    // Catches a regression where the streaming-row state transition diverges from the
    // canonical cancellation idempotency contract.
    @Test
    void doubleDeleteOnInFlightStreamingRun_bothReturn204_andOriginalCancellationReasonPreserved() throws Exception {
        HttpHeaders auth = authenticatedHeaders("double-cancel-runner");
        String agentId = createAgentViaApi(auth, "Streaming Double-Cancel Agent");
        String bearer = bearerFrom(auth);

        fakeModel.respondWithSlowStream(Duration.ofMillis(300),
                "alpha ", "bravo ", "charlie.");

        String sessionId = "session-double-cancel-" + UUID.randomUUID();
        String body = json.writeValueAsString(Map.of(
                "message", "Stream so we can cancel twice.",
                "sessionId", sessionId));

        CompletableFuture<List<ServerSentEvent<String>>> streamFuture = CompletableFuture.supplyAsync(() ->
                sse.post("/api/agents/" + agentId + "/runs/stream", body, bearer, SSE_TIMEOUT));

        String runId = pollForRunningRunId(sessionId);

        ResponseEntity<Void> firstDelete = rest.exchange(
                url("/api/agents/" + agentId + "/runs/" + runId),
                HttpMethod.DELETE, new HttpEntity<>(auth), Void.class);
        assertEquals(HttpStatus.NO_CONTENT, firstDelete.getStatusCode(),
                "first DELETE on an in-flight stream must return 204");

        // Capture the original cancellation reason before issuing the second DELETE. If
        // the second cancel writes through (non-idempotent), error_message would change.
        Awaitility.await().atMost(AWAIT_TIMEOUT).untilAsserted(() -> {
            String status = jdbc.queryForObject(
                    "SELECT status FROM agent_runs WHERE id = ?", String.class, runId);
            assertEquals("CANCELLED", status,
                    "row must be CANCELLED before we issue the second DELETE — otherwise "
                            + "we're testing a race, not idempotency");
        });
        String originalReason = jdbc.queryForObject(
                "SELECT error_message FROM agent_runs WHERE id = ?", String.class, runId);

        ResponseEntity<Void> secondDelete = rest.exchange(
                url("/api/agents/" + agentId + "/runs/" + runId),
                HttpMethod.DELETE, new HttpEntity<>(auth), Void.class);
        assertEquals(HttpStatus.NO_CONTENT, secondDelete.getStatusCode(),
                "second DELETE on a CANCELLED streaming run must also return 204 — matches "
                        + "RunCancellationIdempotencyRuntimeTest's contract for the sync path");

        String reasonAfter = jdbc.queryForObject(
                "SELECT error_message FROM agent_runs WHERE id = ?", String.class, runId);
        assertEquals(originalReason, reasonAfter,
                "error_message must survive both DELETEs — finalizer's already-terminal "
                        + "guard short-circuits before the second write. A change here means "
                        + "the streaming cancellation path diverged from the sync contract.");

        // Drain the stream so the test doesn't leak the background CompletableFuture.
        streamFuture.get(SSE_TIMEOUT.toSeconds() + 5, TimeUnit.SECONDS);
    }

    // ─── helpers ───

    private List<String> parseEventNames(List<ServerSentEvent<String>> raw) throws Exception {
        List<String> names = new ArrayList<>();
        for (ServerSentEvent<String> e : raw) {
            if (e.data() == null || e.data().isBlank()) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = json.readValue(e.data(), Map.class);
            Object eventName = parsed.get("event");
            if (eventName instanceof String s) names.add(s);
        }
        return names;
    }

    private String pollForRunningRunId(String sessionId) {
        // The agent_runs row is created on the subscription thread before the first
        // chunk emits. 5s covers cold-JVM context-boot slack; 50ms intervals keep the
        // race window tight.
        return Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> {
                    List<Map<String, Object>> rows = jdbc.queryForList(
                            "SELECT id, status FROM agent_runs WHERE session_id = ? "
                                    + "ORDER BY created_at DESC LIMIT 1",
                            sessionId);
                    if (rows.isEmpty()) return null;
                    String status = (String) rows.get(0).get("status");
                    // Accept any non-terminal status — RUNNING is the canonical case but
                    // some bootstrap paths persist in QUEUED briefly before RUNNING.
                    if (Set.of("RUNNING", "QUEUED", "PENDING").contains(status)) {
                        return (String) rows.get(0).get("id");
                    }
                    return null;
                }, java.util.Objects::nonNull);
    }

    private void installPermissiveErrorHandler() {
        // TestRestTemplate's default error handler throws on 4xx. We need to observe the
        // status code directly to assert the cancellation contract.
        rest.getRestTemplate().setErrorHandler(new org.springframework.web.client.ResponseErrorHandler() {
            @Override public boolean hasError(org.springframework.http.client.ClientHttpResponse r) { return false; }
            @Override public void handleError(java.net.URI u, org.springframework.http.HttpMethod m, org.springframework.http.client.ClientHttpResponse r) { }
        });
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
        body.put("description", "Streaming cancellation contract fixture");
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
        return authenticateAs(username, username + "@test.local", "pass-cancel-stream-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }
}
