package com.operativus.agentmanager.integration.a2a;

import com.operativus.agentmanager.control.a2a.model.A2aTaskRequest;
import com.operativus.agentmanager.control.repository.AgentRepository;
import com.operativus.agentmanager.core.entity.AgentEntity;
import com.operativus.agentmanager.core.model.TenantConstants;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModel;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Domain Responsibility: Pins the OTel-trace-id propagation contract on the inbound
 *   A2A path (Gap 2.3 in the SDD). {@code A2aTaskRequest} carries an optional
 *   {@code traceId} field that the originating peer populates with its own
 *   distributed-trace correlation id. {@code A2ATaskExecutor.audit()} writes that
 *   id into the {@code a2a_task_events.trace_id} column on every lifecycle row so
 *   operators can reconstruct a cross-boundary trace from the audit table alone,
 *   without requiring the trace backend to be queryable.
 *
 *   The propagation logic in {@code A2ATaskExecutor#audit}:
 *   <pre>
 *   String traceId = request.traceId() != null
 *       ? request.traceId()
 *       : MDC.get(A2aTraceContextFilter.MDC_TRACE_ID_KEY);
 *   </pre>
 *
 *   Two cases:
 *   <ol>
 *     <li>Explicit traceId in the request → every audit row for that taskId
 *         carries that exact value verbatim.</li>
 *     <li>Null traceId in the request → trace_id falls back to whatever MDC
 *         carries (populated by {@code A2aTraceContextFilter} on the HTTP thread
 *         from the {@code traceparent} W3C header or a generated value). The
 *         test asserts only that propagation is consistent across all rows of
 *         the taskId — they all share the same non-explicit value — which is
 *         the operator-facing contract regardless of which source filled it.</li>
 *   </ol>
 *
 * State: Stateless. Per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class A2aTraceContextPropagationRuntimeTest extends BaseIntegrationTest {

    @Autowired private AgentRepository agentRepository;
    @Autowired private FakeChatModel fakeChatModel;

    @BeforeEach
    void resetHarness() {
        fakeChatModel.reset();
        seedDefaultModel();
    }

    /**
     * Case 1 — Explicit traceId in the request body must be persisted verbatim
     * on every audit row (SUBMITTED + WORKING + COMPLETED) for the taskId. This
     * is the load-bearing assertion for cross-boundary trace correlation: an
     * operator querying {@code a2a_task_events WHERE trace_id = '<vendor-id>'}
     * must reconstruct the full local trace of a delegated task.
     */
    @Test
    void submitTask_explicitTraceId_propagatesToEveryAuditRow() {
        String agentId = persistAgent("a2a-trace-explicit-" + UUID.randomUUID());
        fakeChatModel.respondWith("traced result");

        String taskId = "task-trace-" + UUID.randomUUID();
        // Use a marker that cannot match any MDC-derived value.
        String explicitTraceId = "vendor-trace-" + UUID.randomUUID();
        HttpHeaders auth = userHeaders("a2a-trace-explicit");
        A2aTaskRequest request = new A2aTaskRequest(
                taskId, agentId, "trace-explicit input",
                null, "trace-session", explicitTraceId, null);

        rest.exchange(url("/api/v1/a2a/tasks"), HttpMethod.POST,
                new HttpEntity<>(request, auth), String.class);

        Awaitility.await().atMost(15, TimeUnit.SECONDS).pollInterval(150, TimeUnit.MILLISECONDS)
                .until(() -> jdbc.queryForObject(
                        "SELECT COUNT(*) FROM a2a_task_events WHERE task_id = ? AND status = 'COMPLETED'",
                        Integer.class, taskId) >= 1);

        // Give the async audit virtual threads (one per status transition) a
        // beat to all flush. SUBMITTED is audited inline; WORKING + COMPLETED
        // are audited inside the executor's run thread but the audit() method
        // itself spawns yet another virtual thread per write.
        sleepQuietly(400);

        List<String> traceIds = jdbc.queryForList(
                "SELECT trace_id FROM a2a_task_events WHERE task_id = ? ORDER BY event_ts ASC, id ASC",
                String.class, taskId);
        List<String> distinctTraceIds = traceIds.stream().distinct().toList();

        assertAll("A2A trace propagation — explicit traceId on every audit row",
                () -> assertEquals(3, traceIds.size(),
                        "expected 3 audit rows for this taskId (SUBMITTED + WORKING + COMPLETED)"),
                () -> assertEquals(1, distinctTraceIds.size(),
                        "every audit row carries the same trace_id — operator can reconstruct " +
                                "the full local trace by trace_id"),
                () -> assertEquals(explicitTraceId, distinctTraceIds.get(0),
                        "trace_id equals the vendor-supplied traceId verbatim — proves " +
                                "request.traceId() takes precedence over the MDC fallback"));
    }

    /**
     * Case 2 — Null traceId in the request: the audit rows still get a
     * consistent trace_id across all three lifecycle rows. The exact value
     * comes from MDC (populated by {@code A2aTraceContextFilter} from the
     * {@code traceparent} W3C header or a generated value) — the operator-
     * facing contract is "every row of a taskId shares one trace_id," which
     * is what we assert. Whether the source is MDC, generated, or null
     * depends on filter state; this test is robust to all three.
     */
    @Test
    void submitTask_nullTraceId_propagationIsConsistentAcrossAuditRows() {
        String agentId = persistAgent("a2a-trace-null-" + UUID.randomUUID());
        fakeChatModel.respondWith("untraced result");

        String taskId = "task-trace-null-" + UUID.randomUUID();
        HttpHeaders auth = userHeaders("a2a-trace-null");
        A2aTaskRequest request = new A2aTaskRequest(
                taskId, agentId, "trace-null input",
                null, "trace-null-session", null /* traceId */, null);

        rest.exchange(url("/api/v1/a2a/tasks"), HttpMethod.POST,
                new HttpEntity<>(request, auth), String.class);

        Awaitility.await().atMost(15, TimeUnit.SECONDS).pollInterval(150, TimeUnit.MILLISECONDS)
                .until(() -> jdbc.queryForObject(
                        "SELECT COUNT(*) FROM a2a_task_events WHERE task_id = ? AND status = 'COMPLETED'",
                        Integer.class, taskId) >= 1);
        sleepQuietly(400);

        List<String> traceIdsRaw = jdbc.queryForList(
                "SELECT COALESCE(trace_id, '<null>') FROM a2a_task_events WHERE task_id = ? " +
                        "ORDER BY event_ts ASC, id ASC",
                String.class, taskId);
        List<String> distinct = traceIdsRaw.stream().distinct().toList();

        assertAll("A2A trace propagation — null request traceId, consistent fallback",
                () -> assertEquals(3, traceIdsRaw.size(),
                        "expected 3 audit rows for this taskId"),
                () -> assertEquals(1, distinct.size(),
                        "all three audit rows share one trace_id value (MDC-sourced or null) — " +
                                "the operator-facing 'one taskId = one trace' contract holds " +
                                "regardless of whether the fallback resolved to a value or stayed null"));
    }

    // ---------------------------------------------------------------------
    // Fixture helpers
    // ---------------------------------------------------------------------

    private HttpHeaders userHeaders(String prefix) {
        String username = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        HttpHeaders headers = registerLoginWithOrg(username, TenantConstants.DEFAULT_SYSTEM_ORG);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON));
        return headers;
    }

    private String persistAgent(String id) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setOrgId(TenantConstants.DEFAULT_SYSTEM_ORG);
        a.setName("A2A trace-propagation runtime target");
        a.setDescription("Trace-propagation runtime fixture");
        a.setInstructions("Target for OTel traceId propagation coverage.");
        a.setModelId("gpt-4o-mini");
        a.setActive(true);
        a.setMaintenanceMode(false);
        a.setTeam(false);
        a.setTeamMode(null);
        a.setMembers(null);
        return agentRepository.save(a).getId();
    }

    private void seedDefaultModel() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
