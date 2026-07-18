package ai.operativus.agentmanager.integration.observability;

import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Runtime pin for {@code AgentRunEventType.RUN_CANCELLED} emission
 *     on the non-A2A user-initiated cancel path. The A2A cancel path is already covered
 *     by {@code A2aLifecycleAlternatesRuntimeTest.cancel_midFlight_...}; this class extends
 *     coverage to the admin-API cancel route:
 *     {@code POST /api/admin/agents/runs/{runId}/cancel} →
 *     {@code AgentAdminService.cancelRun} → {@code AgentService.cancelRun} →
 *     {@code RunExecutionManager.cancel}.
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 *
 * <p><b>Why the fallback path, not mid-flight</b>: {@code RunExecutionManager.cancel}
 * has two branches —
 * <ol>
 *   <li><b>Mid-flight</b>: run is in {@code activeRuns} → {@code future.cancel(true)}
 *       interrupts the VT, {@code AgentService.run}'s catch routes to CANCELLED via
 *       {@code AgentRunFinalizer}. Tested via the A2A mid-flight pin already.</li>
 *   <li><b>Fallback</b>: run is NOT in {@code activeRuns} (e.g., orphaned after restart,
 *       or seeded directly) → directly calls {@code agentRunFinalizer.finalizeRun} and
 *       {@code publishRunCancelled(USER_INITIATED)}.</li>
 * </ol>
 * This test exercises branch 2, which has the simpler and more deterministic shape:
 * no VT racing, no CountDownLatch coordination.
 */
@Tag("integration")
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class AgentRunCancelledEmissionRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @BeforeEach
    void resetState() {
        seedModel("gpt-4o-mini");
    }

    /**
     * E2 — RUN_CANCELLED emission on user-initiated admin cancel of an orphan RUNNING row.
     *
     * <p>Seeds an {@code agent_runs} row in {@code RUNNING} status (no virtual thread
     * tracking it — simulates an orphan from a previous app run, or a row pending finalization).
     * Calls {@code POST /api/admin/agents/runs/{runId}/cancel}. Asserts:
     * <ol>
     *   <li>The admin endpoint returns 204.</li>
     *   <li>The agent_runs row flips to {@code CANCELLED}.</li>
     *   <li>A {@code RUN_CANCELLED} row lands in {@code agent_run_events} with
     *       {@code classification: "user_initiated"} in the payload — the discrete
     *       label that distinguishes admin-cancel from SLA-driven cancels in metrics
     *       and ops dashboards.</li>
     * </ol>
     */
    @Test
    void userInitiatedAdminCancel_emitsRunCancelledEvent_withUserInitiatedClassification() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        HttpHeaders auth = userHeaders("run-cancelled-" + tag);
        String agentId = createAgent(auth, "RUN_CANCELLED emission probe");
        String orgId = jdbc.queryForObject(
                "SELECT org_id FROM agents WHERE id = ?", String.class, agentId);

        String runId = "run-cancel-" + tag;
        jdbc.update("""
                INSERT INTO agent_runs (id, agent_id, org_id, status, input, created_at, updated_at)
                VALUES (?, ?, ?, 'RUNNING', 'orphan run for cancel probe', now(), now())
                """, runId, agentId, orgId);

        java.time.Instant runStartedAt = java.time.Instant.now().minusSeconds(1);
        ResponseEntity<Void> cancel = rest.exchange(
                url("/api/admin/agents/runs/" + runId + "/cancel"),
                HttpMethod.POST, new HttpEntity<>(auth), Void.class);
        assertTrue(cancel.getStatusCode().is2xxSuccessful(),
                "admin cancel endpoint must return 2xx for a RUNNING orphan — got " + cancel.getStatusCode());

        String terminalStatus = jdbc.queryForObject(
                "SELECT status FROM agent_runs WHERE id = ?", String.class, runId);
        assertTrue("CANCELLED".equals(terminalStatus),
                "agent_runs.status must flip to CANCELLED after the admin cancel — got " + terminalStatus);

        // AgentRunEventBus persists events via a background executor (R-18 isolation:
        // telemetry never blocks the request thread). Await the row landing in DB.
        Awaitility.await("RUN_CANCELLED row lands in agent_run_events")
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> jdbc.queryForObject(
                        """
                        SELECT count(*) FROM agent_run_events
                        WHERE event_type = 'RUN_CANCELLED'
                          AND org_id = ?
                          AND agent_id = ?
                          AND run_id = ?
                          AND event_ts >= ?
                        """,
                        Long.class, orgId, agentId, runId, java.sql.Timestamp.from(runStartedAt)) >= 1L);

        Map<String, Object> payload = jdbc.queryForObject(
                """
                SELECT payload FROM agent_run_events
                WHERE event_type = 'RUN_CANCELLED'
                  AND org_id = ?
                  AND run_id = ?
                ORDER BY event_ts DESC LIMIT 1
                """,
                (rs, n) -> {
                    try {
                        Object raw = rs.getObject(1);
                        if (raw == null) return Map.of();
                        return new com.fasterxml.jackson.databind.ObjectMapper()
                                .readValue(raw.toString(), Map.class);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                orgId, runId);
        assertNotNull(payload, "RUN_CANCELLED row must have a non-null payload");
        assertTrue("user_initiated".equals(payload.get("classification")),
                "RUN_CANCELLED payload must carry classification='user_initiated' (the discrete label "
                        + "for admin-API cancels; distinguishes from SLA-driven cancels in metrics) — "
                        + "got classification=" + payload.get("classification"));
    }

    // ─── helpers ───

    private String createAgent(HttpHeaders auth, String name) {
        String agentId = "agent-" + UUID.randomUUID();
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("agentId", agentId);
        body.put("name", name);
        body.put("description", "RUN_CANCELLED emission fixture");
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
        assertTrue(response.getStatusCode().value() == 201,
                "fixture precondition: agent create must return 201 — got " + response.getStatusCode());
        return agentId;
    }

    private void seedModel(String modelId) {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES (?, ?, 'fake', ?, true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """, modelId, modelId, modelId);
    }

    private HttpHeaders userHeaders(String username) {
        return authenticateAs(username, username + "@test.local", "pass-runcancel-1234", List.of("ROLE_USER", "ROLE_ADMIN"));
    }
}
