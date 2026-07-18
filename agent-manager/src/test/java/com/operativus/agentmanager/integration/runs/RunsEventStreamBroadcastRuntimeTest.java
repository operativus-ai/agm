package com.operativus.agentmanager.integration.runs;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModel;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import com.operativus.agentmanager.integration.support.SseTestClient;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins the {@code GET /api/v1/runs/{runId}/events} SSE event
 *   replay contract. This is the closure path for the FE chat secondary update
 *   channel: after a sync or background run completes (or is cancelled), the FE can
 *   subscribe to the run's event stream and observe the timeline (LLM_REQUEST,
 *   LLM_RESPONSE, RUN_COMPLETE / RUN_CANCELLED, etc.). The SSE controller replays the
 *   {@code agent_run_events} audit table and then polls for new rows.
 *
 *   <p>Two pins:
 *   <ol>
 *     <li>A completed sync run's event stream contains a {@code RUN_COMPLETE} entry
 *         with the expected runId/agentId/sessionId attributes.</li>
 *     <li>A cancelled run's event stream contains a {@code RUN_CANCELLED} entry. The
 *         FE Stop button workflow relies on this for the "run terminated" UX.</li>
 *   </ol>
 *
 *   <p>Tests subscribe AFTER the run has reached its terminal state, so the replay
 *   path supplies all events synchronously. A future test could probe live-follow-up
 *   by subscribing before run completion; out of scope here.
 *
 * State: Stateless. Per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}.
 */
@Tag("integration")
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class RunsEventStreamBroadcastRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    private static final Duration SSE_TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    private FakeChatModel fakeModel;

    private SseTestClient sse;

    @BeforeEach
    void resetStateBeforeTest() {
        truncateDatabase();
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
        fakeModel.reset();
        sse = new SseTestClient("http://localhost:" + port);
        installPermissiveErrorHandler();
    }

    // Pin: after a sync run completes, GET /api/v1/runs/{id}/events replays the
    // RUN_COMPLETE event. This is the contract the FE uses to confirm a run terminated
    // independent of polling the agent_runs row directly.
    @Test
    void syncRunCompletes_eventStreamReplaysRunCompleteEvent() {
        HttpHeaders auth = authenticatedHeaders("event-stream-complete");
        String agentId = createAgentViaApi(auth, "Event Stream Complete Agent");
        String bearer = bearerFrom(auth);

        fakeModel.respondWith("Sync run final reply.");

        Map<String, Object> body = new HashMap<>();
        String sessionId = "session-event-stream-complete-" + UUID.randomUUID();
        body.put("message", "Sync run that completes.");
        body.put("sessionId", sessionId);

        ResponseEntity<Map<String, Object>> runResp = rest.exchange(
                url("/api/agents/" + agentId + "/runs"),
                HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);
        assertEquals(HttpStatus.OK, runResp.getStatusCode(),
                "sync run must complete before we subscribe to its events");

        String runId = (String) runResp.getBody().get("runId");
        assertNotNull(runId, "RunResponse must carry the runId");

        // The run's RUN_COMPLETE event publication on AgentRunEventBus may race the
        // HTTP return. Awaitility on the row count ensures it's persisted to the
        // agent_run_events table before we subscribe.
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Long count = jdbc.queryForObject(
                    "SELECT count(*) FROM agent_run_events WHERE run_id = ? AND event_type = 'RUN_COMPLETE'",
                    Long.class, runId);
            assertEquals(1L, count,
                    "agent_run_events must carry exactly one RUN_COMPLETE row for this run");
        });

        List<ServerSentEvent<String>> frames = sse.get(
                "/api/v1/runs/" + runId + "/events", bearer, SSE_TIMEOUT);

        Set<String> eventTypes = collectEventTypes(frames);
        assertTrue(eventTypes.contains("RUN_COMPLETE"),
                "SSE replay must include RUN_COMPLETE for a completed sync run — eventTypes seen: "
                        + eventTypes + ". Missing here means the SSE controller's replay path "
                        + "isn't surfacing the bus event from agent_run_events.");
        assertTrue(eventTypes.contains("LLM_REQUEST") || eventTypes.contains("LLM_RESPONSE"),
                "SSE replay should include LLM lifecycle events for a successful run — eventTypes: "
                        + eventTypes);
    }

    // Post-fix: RUN_CANCELLED is now in RunEventSseService.TERMINAL_EVENT_TYPES, so a
    // GET /api/v1/runs/{id}/events SSE subscription on a cancelled run auto-closes
    // after replaying the RUN_CANCELLED event. This test subscribes AFTER cancellation
    // and asserts both the row persistence AND the SSE replay terminate cleanly.
    @Test
    void cancelledRun_eventStreamReplaysRunCancelledEventAndAutoCloses() {
        HttpHeaders auth = registerLoginWithOrg("event-stream-cancel-" + UUID.randomUUID().toString().substring(0, 6),
                "org-event-stream-cancel");
        String agentId = createAgentViaApi(auth, "Event Stream Cancel Agent");
        String bearer = bearerFrom(auth);

        String tag = UUID.randomUUID().toString().substring(0, 8);
        String runId = "run-event-cancel-" + tag;
        String sessionId = "session-event-cancel-" + tag;
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, user_id, org_id, agent_id, created_at, updated_at)
                VALUES (?, 'event-cancel-user', 'org-event-stream-cancel', ?, now(), now())
                """, sessionId, agentId);
        jdbc.update("""
                INSERT INTO agent_runs (id, agent_id, session_id, input, user_id, org_id,
                                        status, created_at, updated_at)
                VALUES (?, ?, ?, 'seeded for cancellation', 'event-cancel-user', 'org-event-stream-cancel',
                        'RUNNING', now(), now())
                """, runId, agentId, sessionId);

        ResponseEntity<Void> deleteResp = rest.exchange(
                url("/api/agents/" + agentId + "/runs/" + runId),
                HttpMethod.DELETE, new HttpEntity<>(auth), Void.class);
        assertEquals(HttpStatus.NO_CONTENT, deleteResp.getStatusCode());

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Long cancelledCount = jdbc.queryForObject(
                    "SELECT count(*) FROM agent_run_events WHERE run_id = ? AND event_type = 'RUN_CANCELLED'",
                    Long.class, runId);
            assertEquals(1L, cancelledCount,
                    "agent_run_events must carry exactly one RUN_CANCELLED row after the DELETE");
        });

        // Live SSE subscribe — RUN_CANCELLED is now terminal so the controller auto-closes.
        List<ServerSentEvent<String>> frames = sse.get(
                "/api/v1/runs/" + runId + "/events", bearer, SSE_TIMEOUT);

        Set<String> eventTypes = collectEventTypes(frames);
        assertTrue(eventTypes.contains("RUN_CANCELLED"),
                "SSE replay must include RUN_CANCELLED — eventTypes seen: " + eventTypes
                        + ". Without this the FE cancel-UX has no signal that the run terminated.");
        assertTrue(!eventTypes.contains("RUN_COMPLETE"),
                "a cancelled run must NOT emit RUN_COMPLETE — both would indicate a race / dual-finalize bug");
    }

    // ─── helpers ───

    private Set<String> collectEventTypes(List<ServerSentEvent<String>> frames) {
        Set<String> types = new java.util.HashSet<>();
        for (ServerSentEvent<String> f : frames) {
            String data = f.data();
            if (data == null || data.isBlank()) continue;
            // RunEventSseService emits AgentRunEvent JSON; we just need the "eventType" field.
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = json.readValue(data, Map.class);
                Object t = parsed.get("eventType");
                if (t == null) t = parsed.get("type");
                if (t instanceof String s) types.add(s);
            } catch (Exception ignored) {
                // Some replay frames may carry non-JSON payload (e.g. comments) — skip.
            }
        }
        return types;
    }

    private void installPermissiveErrorHandler() {
        rest.getRestTemplate().setErrorHandler(new org.springframework.web.client.ResponseErrorHandler() {
            @Override public boolean hasError(org.springframework.http.client.ClientHttpResponse r) { return false; }
            @Override public void handleError(java.net.URI u, org.springframework.http.HttpMethod m, org.springframework.http.client.ClientHttpResponse r) { }
        });
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
        body.put("description", "Event stream broadcast fixture");
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
                "fixture precondition: agent must exist");
        return agentId;
    }

    private HttpHeaders authenticatedHeaders(String username) {
        return authenticateAs(username, username + "@test.local", "pass-event-stream-1234",
                // ROLE_ADMIN required to create the fixture agent via /api/admin/agents (gated since #969).
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }
}
