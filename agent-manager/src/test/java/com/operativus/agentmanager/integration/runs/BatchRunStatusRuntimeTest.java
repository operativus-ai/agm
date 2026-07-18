package com.operativus.agentmanager.integration.runs;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins the observable contract of
 *   {@code GET /api/agents/{agentId}/runs/status?runIds=…} —
 *   {@link com.operativus.agentmanager.control.controller.AgentsController#getRunStatusBatch}.
 *   This endpoint unlocks the FE {@code ActiveRunsTracker} N+1 polling loop and is now the
 *   ONLY way to read run status after the single-run endpoint was dropped in PR #501.
 *
 *   <p>Design intent from the controller's javadoc (lines 158–167):
 *   <ul>
 *     <li>"The agentId path parameter is a routing hint; the actual filter is runId-only."
 *         The endpoint does NOT enforce tenant isolation at the row-filter level — it relies
 *         on runIds being unguessable UUIDs as the security boundary. This test pins that
 *         choice so a future "filter by orgId" refactor surfaces as a test failure rather
 *         than a silent behavior change.</li>
 *     <li>{@code findByIdIn} is a single bulk query — replaces 10 concurrent × 3s polling
 *         (~200 req/min) with one batched call per tick (~20 req/min).</li>
 *   </ul>
 *
 *   <p>Boundary contract:
 *   <ul>
 *     <li>Empty / null runIds → {@code 200 OK} with empty list (no 4xx — empty input is valid)</li>
 *     <li>Unknown runIds are silently omitted from the response (no 404, no error)</li>
 *     <li>{@code runIds.size() &gt; 100} → 400 via {@code IllegalArgumentException} →
 *         {@code GlobalExceptionHandler.handleIllegalArgumentException}</li>
 *     <li>{@code agentId} path parameter does NOT filter — runs from any agent are returned
 *         as long as the runId is supplied (this is the "routing hint" contract above)</li>
 *   </ul>
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class BatchRunStatusRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> JSON_LIST =
            new ParameterizedTypeReference<>() {};

    @BeforeEach
    void seedModelRow() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    @Test
    void emptyRunIdsParam_returns200_withEmptyList() {
        HttpHeaders auth = newCaller("empty");
        String agentId = seedAgent("empty-batch");

        ResponseEntity<List<Map<String, Object>>> resp = rest.exchange(
                url("/api/agents/" + agentId + "/runs/status?runIds="),
                HttpMethod.GET,
                new HttpEntity<>(auth),
                JSON_LIST);

        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "empty runIds must NOT 4xx — empty input is valid; got " + resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertTrue(resp.getBody().isEmpty(),
                "empty runIds must return [] — got " + resp.getBody().size() + " items");
    }

    @Test
    void knownRunIds_returnsAllMatchingRows() {
        HttpHeaders auth = newCaller("known");
        String agentId = seedAgent("known-batch");
        String sessionId = seedSession("sess-known-batch", agentId);

        String r1 = seedRun("r1-" + UUID.randomUUID(), agentId, sessionId, "RUNNING");
        String r2 = seedRun("r2-" + UUID.randomUUID(), agentId, sessionId, "COMPLETED");
        String r3 = seedRun("r3-" + UUID.randomUUID(), agentId, sessionId, "FAILED");

        String queryRunIds = String.join(",", List.of(r1, r2, r3));

        ResponseEntity<List<Map<String, Object>>> resp = rest.exchange(
                url("/api/agents/" + agentId + "/runs/status?runIds=" + queryRunIds),
                HttpMethod.GET,
                new HttpEntity<>(auth),
                JSON_LIST);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        List<Map<String, Object>> body = resp.getBody();
        assertNotNull(body);
        assertEquals(3, body.size(),
                "all 3 known runIds must be returned (any order); got " + body.size());

        List<String> returnedIds = body.stream()
                .map(row -> String.valueOf(row.get("id")))
                .toList();
        assertAll("returned rows cover the queried ids",
                () -> assertTrue(returnedIds.contains(r1), "missing r1"),
                () -> assertTrue(returnedIds.contains(r2), "missing r2"),
                () -> assertTrue(returnedIds.contains(r3), "missing r3"));
    }

    @Test
    void mixOfKnownAndUnknownRunIds_returnsOnlyKnownRows_unknownSilentlyOmitted() {
        HttpHeaders auth = newCaller("mixed");
        String agentId = seedAgent("mixed-batch");
        String sessionId = seedSession("sess-mixed-batch", agentId);

        String known = seedRun("known-" + UUID.randomUUID(), agentId, sessionId, "RUNNING");
        String ghost1 = UUID.randomUUID().toString();
        String ghost2 = UUID.randomUUID().toString();

        ResponseEntity<List<Map<String, Object>>> resp = rest.exchange(
                url("/api/agents/" + agentId + "/runs/status?runIds=" + known + "," + ghost1 + "," + ghost2),
                HttpMethod.GET,
                new HttpEntity<>(auth),
                JSON_LIST);

        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "unknown runIds must NOT cause 404 — the endpoint silently omits missing rows; "
                        + "this matches the FE polling contract where stale tracker entries don't "
                        + "trigger error toasts");
        assertEquals(1, resp.getBody().size(),
                "exactly the known row must be returned; unknowns are silently dropped; got "
                        + resp.getBody().size());
        assertEquals(known, resp.getBody().get(0).get("id"),
                "the returned row must be the known one");
    }

    @Test
    void over100RunIds_returns400_viaIllegalArgumentExceptionMapping() {
        HttpHeaders auth = newCaller("oversize");
        String agentId = seedAgent("oversize-batch");

        // 101 random UUIDs — none need to exist in DB; the size check fires before the query.
        String oversizeParam = IntStream.range(0, 101)
                .mapToObj(i -> UUID.randomUUID().toString())
                .collect(Collectors.joining(","));

        ResponseEntity<String> resp = rest.exchange(
                url("/api/agents/" + agentId + "/runs/status?runIds=" + oversizeParam),
                HttpMethod.GET,
                new HttpEntity<>(auth),
                String.class);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                "controller throws IllegalArgumentException for >100 runIds; "
                        + "GlobalExceptionHandler maps it to 400; got " + resp.getStatusCode());
        assertNotNull(resp.getBody(), "400 response must carry a body for client triage");
        assertTrue(resp.getBody().contains("100") || resp.getBody().toLowerCase().contains("max"),
                "400 body should name the limit (100) so client devs can adjust batch sizes; "
                        + "got '" + resp.getBody() + "'");
    }

    @Test
    void agentIdPathParam_isRoutingHintOnly_doesNotFilterRows() {
        HttpHeaders auth = newCaller("routing");
        String agentA = seedAgent("routing-a");
        String agentB = seedAgent("routing-b");
        String sessionA = seedSession("sess-routing-a", agentA);
        String sessionB = seedSession("sess-routing-b", agentB);

        String runOnAgentA = seedRun("run-a-" + UUID.randomUUID(), agentA, sessionA, "RUNNING");
        String runOnAgentB = seedRun("run-b-" + UUID.randomUUID(), agentB, sessionB, "RUNNING");

        // Query agent A's URL but pass agent B's runId — the doc says this must still return
        // the row. agentId is "a routing hint" per controller javadoc:158-167.
        ResponseEntity<List<Map<String, Object>>> resp = rest.exchange(
                url("/api/agents/" + agentA + "/runs/status?runIds=" + runOnAgentB + "," + runOnAgentA),
                HttpMethod.GET,
                new HttpEntity<>(auth),
                JSON_LIST);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(2, resp.getBody().size(),
                "BOTH runs must be returned — agentId path param is a routing hint, not a filter. "
                        + "If this test fails because only 1 row is returned, the design intent has "
                        + "changed and the endpoint now enforces agentId filtering. That's a "
                        + "breaking change for the FE ActiveRunsTracker polling loop; update the "
                        + "design or update this assertion deliberately.");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private HttpHeaders newCaller(String label) {
        String username = "batch-status-" + label + "-" + UUID.randomUUID();
        return authenticateAs(username, username + "@t.local",
                "pwd-batch-1234", List.of("ROLE_USER"));
    }

    private String seedAgent(String label) {
        String id = "agent-batch-" + label + "-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, now(), now())
                """, id, "Batch test agent " + label);
        return id;
    }

    private String seedSession(String sessionId, String agentId) {
        jdbc.update("""
                INSERT INTO agent_sessions (session_id, agent_id, user_id, org_id, created_at, updated_at)
                VALUES (?, ?, 'batch-user', 'batch-org', now(), now())
                ON CONFLICT (session_id) DO NOTHING
                """, sessionId, agentId);
        return sessionId;
    }

    private String seedRun(String runId, String agentId, String sessionId, String status) {
        jdbc.update("""
                INSERT INTO agent_runs (id, agent_id, session_id, user_id, org_id,
                                        input, status, version, created_at, updated_at)
                VALUES (?, ?, ?, 'batch-user', 'batch-org',
                        ?, ?, 0, now(), now())
                """, runId, agentId, sessionId, "input for " + runId, status);
        return runId;
    }
}
