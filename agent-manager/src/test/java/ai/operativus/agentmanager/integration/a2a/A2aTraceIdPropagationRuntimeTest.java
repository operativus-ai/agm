package ai.operativus.agentmanager.integration.a2a;

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
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Domain Responsibility: Pins {@code trace_id} propagation across the A2A audit pipeline.
 *   {@code A2AController.submitTask} resolves {@code effectiveTraceId} on the HTTP thread
 *   (where {@code A2aTraceContextFilter} has bound MDC) and stashes it on the request so
 *   every downstream {@code audit()} call sees the same value. Without that stash, the
 *   virtual threads spawned by the executor would see an empty MDC and fragment the
 *   {@code trace_id} across audit rows — operators reconstructing a task's trail by
 *   trace would only find the SUBMITTED row.
 *
 *   The fix is at A2AController submitTask (lines ~228-232 on `main`). This canary
 *   pins the contract: every {@code a2a_task_events} row for the same task must carry
 *   the SAME non-null trace_id.
 * State: Stateless.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class A2aTraceIdPropagationRuntimeTest extends BaseIntegrationTest {

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
    void submitTask_callerSuppliedTraceId_propagates_to_all_audit_rows_for_thatTaskId() {
        HttpHeaders auth = userHeaders("a2a-trace-supplied");
        String agentId = createAgent(auth, "trace-supplied target");
        fakeModel.respondWith("ack");

        String taskId  = "task-" + UUID.randomUUID();
        String traceId = "trace-" + UUID.randomUUID();

        Map<String, Object> body = new HashMap<>();
        body.put("taskId", taskId);
        body.put("targetAgentId", agentId);
        body.put("input", "trace propagation drill");
        body.put("initiatingAgentId", "peer-init-trace");
        body.put("sessionId", "sess-" + UUID.randomUUID());
        body.put("traceId", traceId);

        ResponseEntity<String> sse = rest.exchange(
                url("/api/v1/a2a/tasks"), HttpMethod.POST, new HttpEntity<>(body, auth), String.class);
        assertEquals(200, sse.getStatusCode().value());

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> jdbc.queryForObject(
                        "SELECT COUNT(*) FROM a2a_task_events WHERE task_id = ? AND status = 'COMPLETED'",
                        Integer.class, taskId) > 0);

        List<String> traceIds = jdbc.queryForList(
                "SELECT trace_id FROM a2a_task_events WHERE task_id = ?", String.class, taskId);

        assertThat(traceIds).as("all rows for this taskId must share the same caller-supplied trace_id; "
                + "fragmentation would mean a virtual thread couldn't see the HTTP-thread MDC and the "
                + "stash on effectiveRequest didn't carry it across")
                .isNotEmpty()
                .allMatch(traceId::equals);

        // Cross-check: the distinct count must be 1.
        long distinct = traceIds.stream().distinct().count();
        assertEquals(1, distinct, "exactly one distinct trace_id across all lifecycle audit rows");
    }

    @Test
    void submitTask_omittedTraceId_resolvesFromHttpMdc_andStillPropagates_acrossAuditRows() {
        HttpHeaders auth = userHeaders("a2a-trace-derived");
        String agentId = createAgent(auth, "trace-derived target");
        fakeModel.respondWith("ack");

        String taskId = "task-" + UUID.randomUUID();

        Map<String, Object> body = new HashMap<>();
        body.put("taskId", taskId);
        body.put("targetAgentId", agentId);
        body.put("input", "trace propagation drill — no caller trace");
        body.put("initiatingAgentId", "peer-init-trace-derived");
        body.put("sessionId", "sess-" + UUID.randomUUID());
        // intentionally NO traceId — controller must derive from MDC

        ResponseEntity<String> sse = rest.exchange(
                url("/api/v1/a2a/tasks"), HttpMethod.POST, new HttpEntity<>(body, auth), String.class);
        assertEquals(200, sse.getStatusCode().value());

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> jdbc.queryForObject(
                        "SELECT COUNT(*) FROM a2a_task_events WHERE task_id = ? AND status = 'COMPLETED'",
                        Integer.class, taskId) > 0);

        List<String> traceIds = jdbc.queryForList(
                "SELECT trace_id FROM a2a_task_events WHERE task_id = ?", String.class, taskId);

        // The MDC value depends on whether A2aTraceContextFilter set one; if not, all rows
        // share NULL — but they must still be uniformly the same value (no fragmentation).
        long distinct = traceIds.stream()
                .map(t -> t == null ? "__NULL__" : t)
                .collect(Collectors.toSet())
                .size();
        assertEquals(1, distinct,
                "even when trace_id is null (MDC absent), all rows must share that null — "
                        + "fragmentation would mean SOME rows had a value and OTHERS were null, "
                        + "which would only happen if MDC was read on the v-thread instead of the "
                        + "HTTP thread");
    }

    private String createAgent(HttpHeaders auth, String name) {
        String agentId = "agent-" + UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId);
        body.put("name", name);
        body.put("description", "trace-id propagation fixture");
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
        assertEquals(201, response.getStatusCode().value());
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
        return authenticateAs(username, username + "@test.local", "pass-a2a-trace", List.of("ROLE_USER", "ROLE_ADMIN"));
    }
}
