package ai.operativus.agentmanager.integration.observability;

import ai.operativus.agentmanager.core.event.AgentRunEventType;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModel;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import ai.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Runtime pin for {@code AgentRunEventType.RUN_FAILED} emission
 *     on the unhappy-path of an agent run. Production code publishes this event from
 *     {@code AgentService.publishRunLifecycleEvent} when the run's terminal status maps
 *     to {@code FAILED} (e.g., upstream provider exception, context-limit, tool error).
 *     Before this test, the only assertion of {@code RUN_FAILED} emission lived at the
 *     unit level via mock {@code AgentRunEventBus} invocations.
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 *
 * <p>Why a dedicated test class: the existing observability surface
 * ({@code FinOpsRuntimeTest}, {@code BudgetAlertsRuntimeTest},
 * {@code BudgetExceededAlertBridgeRuntimeTest}) all cover happy-path or
 * BUDGET-specific events. {@code A2aLifecycleAlternatesRuntimeTest.cancel_midFlight_...}
 * is the only existing runtime pin on a non-happy lifecycle event (RUN_CANCELLED), and
 * it's A2A-scoped. This class extends coverage to a regular agent-run failure.
 */
@Tag("integration")
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class AgentRunFailedEmissionRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Autowired private FakeChatModel fakeModel;

    @BeforeEach
    void resetState() {
        fakeModel.reset();
        seedModel("gpt-4o-mini");
    }

    /**
     * E1 — RUN_FAILED emission when the upstream model throws.
     *
     * <p>Configures {@code FakeChatModel} to throw a {@link RuntimeException} on the
     * next call (simulating a provider 5xx, quota breach, or unhandled exception path).
     * Posts an agent run; expects a 5xx response (the global exception handler routes
     * to {@code 500 INTERNAL_SERVER_ERROR} for unhandled paths, but the status is not
     * the focus of this test). Asserts that a {@code RUN_FAILED} row lands in
     * {@code agent_run_events} for the halted run with the simulated error in the
     * payload's {@code errorClass} / {@code errorMessage} keys.
     */
    @Test
    void runFailedFromUpstreamModelException_emitsRunFailedEvent_withErrorContext() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        HttpHeaders auth = userHeaders("run-failed-" + tag);
        String agentId = createAgent(auth, "RUN_FAILED emission probe");
        String orgId = jdbc.queryForObject(
                "SELECT org_id FROM agents WHERE id = ?", String.class, agentId);

        fakeModel.respondWith(prompt -> {
            throw new RuntimeException("simulated upstream provider 5xx for runtime pin");
        });

        java.time.Instant runStartedAt = java.time.Instant.now().minusSeconds(1);
        ResponseEntity<Map<String, Object>> run = runAgent(
                auth, agentId, "trigger run failure probe", "session-failed-" + tag);
        assertTrue(run.getStatusCode().is5xxServerError() || run.getStatusCode().value() == 500,
                "agent run with a model that throws must surface as 5xx — got " + run.getStatusCode());

        // The RUN_FAILED row must land in agent_run_events with the simulated error context.
        // AgentRunEventBus persists lifecycle events asynchronously on a virtual thread (with
        // retries), so the HTTP response can return 5xx before the row is committed. Poll for it
        // instead of reading once — under full-suite load the async write lags the immediate query.
        java.sql.Timestamp since = java.sql.Timestamp.from(runStartedAt);
        org.awaitility.Awaitility.await("RUN_FAILED row in agent_run_events")
                .atMost(java.time.Duration.ofSeconds(10))
                .pollInterval(java.time.Duration.ofMillis(100))
                .until(() -> {
                    Long n = jdbc.queryForObject(
                            """
                            SELECT count(*) FROM agent_run_events
                            WHERE event_type = 'RUN_FAILED'
                              AND org_id = ?
                              AND agent_id = ?
                              AND event_ts >= ?
                            """,
                            Long.class, orgId, agentId, since);
                    return n != null && n >= 1L;
                });

        // Confirm the payload carries the error context (errorClass / errorMessage) so operators
        // can root-cause from the audit row without correlating to application logs.
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = jdbc.queryForObject(
                """
                SELECT payload FROM agent_run_events
                WHERE event_type = 'RUN_FAILED'
                  AND org_id = ?
                  AND agent_id = ?
                  AND event_ts >= ?
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
                orgId, agentId, java.sql.Timestamp.from(runStartedAt));
        assertNotNull(payload, "RUN_FAILED row must have a non-null payload");
        assertTrue(payload.containsKey("errorClass") || payload.containsKey("errorMessage"),
                "RUN_FAILED payload must carry errorClass and/or errorMessage so operators can root-cause "
                        + "from the audit row alone; got keys: " + payload.keySet());
    }

    // ─── helpers (mirror FinOpsRuntimeTest pattern) ───

    private ResponseEntity<Map<String, Object>> runAgent(HttpHeaders auth, String agentId, String message, String sessionId) {
        Map<String, Object> body = new HashMap<>();
        body.put("message", message);
        body.put("sessionId", sessionId);
        return rest.exchange(url("/api/agents/" + agentId + "/runs"),
                HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);
    }

    private String createAgent(HttpHeaders auth, String name) {
        String agentId = "agent-" + UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId);
        body.put("name", name);
        body.put("description", "RUN_FAILED emission fixture");
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
        assertEquals(201, response.getStatusCode().value(),
                "fixture precondition: agent create must return 201");
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
        return authenticateAs(username, username + "@test.local", "pass-runfailed-1234", List.of("ROLE_USER", "ROLE_ADMIN"));
    }
}
