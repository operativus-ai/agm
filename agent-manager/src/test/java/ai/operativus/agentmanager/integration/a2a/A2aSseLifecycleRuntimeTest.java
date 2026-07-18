package ai.operativus.agentmanager.integration.a2a;

import ai.operativus.agentmanager.control.a2a.model.A2aTaskStatus;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModel;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import ai.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins the FULL A2A task lifecycle ordering — every successful
 *   {@code POST /api/v1/a2a/tasks} must produce three audit rows in chronological order:
 *   {@code SUBMITTED} → {@code WORKING} → {@code COMPLETED}.
 *   {@link A2aMeshRuntimeTest#submitTask_returnsSseStream_andPersistsLifecycleAuditRow}
 *   only asserts {@code count > 0}; this test asserts the exact triplet and the order
 *   so that a regression which (a) silently merges states, (b) skips {@code WORKING}, or
 *   (c) writes terminal {@code COMPLETED} before {@code WORKING} surfaces immediately.
 *
 *   The SSE response itself is also asserted to carry the {@code text/event-stream}
 *   content-type — the SSE produces() contract is the only guarantee FE consumers have
 *   that they can subscribe via {@code EventSource} / {@code fetchEventSource}.
 * State: Stateless.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class A2aSseLifecycleRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Autowired private FakeChatModel fakeModel;

    @BeforeEach
    void resetState() {
        fakeModel.reset();
        seedModel("gpt-4o-mini");
        jdbc.update("TRUNCATE TABLE a2a_task_events");
    }

    @Test
    void submitTask_audit_rows_appear_in_order_SUBMITTED_WORKING_COMPLETED() {
        HttpHeaders auth = userHeaders("a2a-sse-lifecycle");
        String agentId = createAgent(auth, "sse-lifecycle target agent");
        fakeModel.respondWith("ack from fake model");

        String taskId = "task-" + UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("taskId", taskId);
        body.put("targetAgentId", agentId);
        body.put("input", "drive the full lifecycle");
        body.put("initiatingAgentId", "peer-init-sse-lifecycle");
        body.put("sessionId", "sess-" + UUID.randomUUID());

        ResponseEntity<String> sse = rest.exchange(
                url("/api/v1/a2a/tasks"), HttpMethod.POST, new HttpEntity<>(body, auth), String.class);

        // Three rows MUST land — the dispatch is via virtual thread so we poll.
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> jdbc.queryForObject(
                        "SELECT COUNT(*) FROM a2a_task_events WHERE task_id = ?",
                        Integer.class, taskId) >= 3);

        // Order assertion: read all rows for this taskId by event_ts ASC. The list of
        // status values must start with SUBMITTED, then WORKING, then COMPLETED.
        List<A2aTaskStatus> statusesInOrder = jdbc.query(
                "SELECT status FROM a2a_task_events WHERE task_id = ? ORDER BY event_ts ASC, id ASC",
                (rs, i) -> A2aTaskStatus.valueOf(rs.getString(1)),
                taskId);

        assertAll("happy-path lifecycle must emit SUBMITTED → WORKING → COMPLETED in chronological order",
                () -> assertEquals(200, sse.getStatusCode().value(),
                        "POST /tasks returns 200 — the SseEmitter response was constructed"),
                () -> assertTrue(sse.getHeaders().getContentType() != null
                                && sse.getHeaders().getContentType().toString().startsWith("text/event-stream"),
                        "Content-Type must be text/event-stream — the SSE produces() wire contract"),
                () -> assertTrue(statusesInOrder.size() >= 3,
                        "at least 3 lifecycle rows must be present; got " + statusesInOrder),
                () -> assertEquals(A2aTaskStatus.SUBMITTED, statusesInOrder.get(0),
                        "first row must be SUBMITTED (controller writes this on the HTTP thread before "
                                + "spawning the v-thread)"),
                () -> assertEquals(A2aTaskStatus.WORKING, statusesInOrder.get(1),
                        "second row must be WORKING (executor writes this when v-thread begins run())"),
                () -> assertEquals(A2aTaskStatus.COMPLETED, statusesInOrder.get(statusesInOrder.size() - 1),
                        "final row must be COMPLETED — terminal status for the happy path"));
    }

    private String createAgent(HttpHeaders auth, String name) {
        String agentId = "agent-" + UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId);
        body.put("name", name);
        body.put("description", "A2A SSE lifecycle test fixture");
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
                "fixture precondition: agent create must return 201 before the SSE lifecycle test runs");
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
        return authenticateAs(username, username + "@test.local", "pass-a2a-sse", List.of("ROLE_USER", "ROLE_ADMIN"));
    }
}
