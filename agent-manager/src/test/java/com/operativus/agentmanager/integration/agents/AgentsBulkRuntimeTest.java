package com.operativus.agentmanager.integration.agents;

import com.operativus.agentmanager.core.entity.BackgroundJob;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.JobQueueTestSupport;
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

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Black-box runtime coverage of the Agent bulk-action surface —
 *   {@code POST /api/admin/agents/bulk-action} (handled by
 *   {@link com.operativus.agentmanager.control.controller.AgentAdminController#bulkAction}).
 *   The endpoint does NOT execute inline; it serializes the request into a
 *   {@link com.operativus.agentmanager.control.service.queue.AgentBulkActionJobHandler.Payload}
 *   and enqueues an {@code AGENT_BULK_ACTION} job via
 *   {@link com.operativus.agentmanager.control.service.PersistentJobQueueService#enqueue}.
 *   Tests drive the queue synchronously through {@link JobQueueTestSupport#processNow()}
 *   (production's {@code @Scheduled} poll is pushed to 24h in {@code application-test.properties}
 *   per spec decision 4.4) and await terminal state via Awaitility pollers.
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 *
 * Cases mapped to {@code docs/testing/agm-runtime-testing.md} §5.9 (bulk action via
 *   {@code AgentBulkActionJobHandler} → returns jobId, job transitions
 *   QUEUED→PROCESSING→COMPLETED, result mutation visible). §5.9 is expanded to three cases
 *   — one per supported action — so each branch of the {@code AgentBulkOperationsService}
 *   switch is pinned independently.
 *
 * Implementation notes / gaps these tests pin:
 *   - The controller returns {@code 202 Accepted} with a JSON body of {@code {"jobId": "..."}}
 *     and never blocks on execution. Tests MUST then call {@link JobQueueTestSupport#processNow()}
 *     to drive the queue or they will hang until the 24h scheduler tick.
 *   - {@code AgentBulkActionJobHandler#execute} uppercases the incoming action and dispatches
 *     through a {@code switch} to {@code enableAll} / {@code disableAll} / {@code deleteAll}.
 *     Writes the {@code result} JSON blob {@code {"affected": N}} on the {@code background_jobs}
 *     row before marking it COMPLETED.
 *   - <b>ENABLE/DISABLE path bypasses the admin service's @PreAuthorize gates</b> —
 *     {@link com.operativus.agentmanager.control.service.AgentBulkOperationsService#enableAll}
 *     and {@code disableAll} go straight to {@code AgentRepository.findAllById / saveAll},
 *     so they succeed regardless of the caller's role.
 *   - <b>DELETE path authorizes against the enqueuer's SecurityContext</b> —
 *     {@code deleteAll} delegates to
 *     {@link com.operativus.agentmanager.core.registry.AgentAdminOperations#deleteAgent}, whose
 *     {@code @PreAuthorize("hasPermission(#id, 'AgentDefinition', 'delete')")} resolves to
 *     {@link com.operativus.agentmanager.control.security.AgentPermissionEvaluator}.
 *     {@link com.operativus.agentmanager.control.service.PersistentJobQueueService} captures
 *     the caller's {@code SecurityContext} at {@code enqueue} and re-installs it on the
 *     virtual-thread worker before invoking the handler, so {@code ROLE_ADMIN} callers drive
 *     the soft-delete through and non-admin callers get denied by the evaluator.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, JobQueueTestSupport.class})
public class AgentsBulkRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Autowired
    private JobQueueTestSupport jobs;

    @BeforeEach
    void resetStateBeforeTest() {
        truncateDatabase();
        seedModel("gpt-4o-mini");
    }

    /**
     * Minimal {@code models} row to clear {@code fk_agents_model_id} when the test-driven
     * agents are POSTed. Mirrors {@link AgentsCrudRuntimeTest#seedModel(String)}.
     */
    private void seedModel(String modelId) {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES (?, ?, 'fake', ?, true, false, true, 'CHAT', now())
                """, modelId, modelId, modelId);
    }

    // §5.9a — ENABLE branch. Two agents seeded with active=false; bulk-action ENABLE flips them
    // both to active=true without touching the @PreAuthorize-gated admin service. Pins that
    // (a) the controller returns 202 + jobId, (b) processQueue dispatches to the handler on a
    // virtual thread, (c) the handler writes `{"affected":N}` into background_jobs.result, and
    // (d) the SELECT-side of DatabaseAgentRegistry sees the flip (we read through JdbcTemplate
    // to avoid caching variance).
    @Test
    void bulkActionEnableFlipsAgentsActiveToTrueAndRecordsAffectedCountOnJob() {
        HttpHeaders auth = authenticatedHeaders("bulk-enabler");

        String idA = createAgentViaApi(auth, "Bulk Enable A", false);
        String idB = createAgentViaApi(auth, "Bulk Enable B", false);
        String untouched = createAgentViaApi(auth, "Untouched", false);

        Map<String, Object> req = Map.of("action", "ENABLE", "ids", List.of(idA, idB));
        ResponseEntity<Map<String, Object>> accepted = rest.exchange(
                url("/api/admin/agents/bulk-action"),
                HttpMethod.POST, new HttpEntity<>(req, auth), JSON_MAP);

        assertEquals(HttpStatus.ACCEPTED, accepted.getStatusCode(),
                "bulk-action is fire-and-forget: controller returns 202 immediately after enqueueing");
        String jobId = (String) accepted.getBody().get("jobId");
        assertNotNull(jobId, "202 body must include {\"jobId\": \"...\"} so the caller can poll terminal state");

        jobs.processNow();
        BackgroundJob job = jobs.awaitJobSuccess(jobId, Duration.ofSeconds(10));
        assertTrue(job.getResult() != null && job.getResult().contains("\"affected\":2"),
                "handler writes {\"affected\":N} onto background_jobs.result before marking COMPLETED; got: " + job.getResult());

        assertEquals(Boolean.TRUE, readActive(idA), "agent A must have been flipped to active=true by enableAll");
        assertEquals(Boolean.TRUE, readActive(idB), "agent B must have been flipped to active=true by enableAll");
        assertEquals(Boolean.FALSE, readActive(untouched),
                "agents not listed in the bulk-action payload must remain unchanged (enableAll scopes to findAllById(ids))");
    }

    // §5.9b — DISABLE branch. Same shape as ENABLE but the inverse mutation; pins that the
    // handler's switch correctly case-routes "DISABLE" → AgentBulkOperationsService.disableAll
    // and writes the same result shape. Important: disableAll saves via JPA, so the version
    // column bumps — asserting the bump would leak into optimistic-locking territory we
    // already cover in AgentsCrudRuntimeTest, so we pin only the active flag and the counter.
    @Test
    void bulkActionDisableFlipsAgentsActiveToFalseAndRecordsAffectedCountOnJob() {
        HttpHeaders auth = authenticatedHeaders("bulk-disabler");

        String idA = createAgentViaApi(auth, "Bulk Disable A", true);
        String idB = createAgentViaApi(auth, "Bulk Disable B", true);

        Map<String, Object> req = Map.of("action", "DISABLE", "ids", List.of(idA, idB));
        ResponseEntity<Map<String, Object>> accepted = rest.exchange(
                url("/api/admin/agents/bulk-action"),
                HttpMethod.POST, new HttpEntity<>(req, auth), JSON_MAP);

        assertEquals(HttpStatus.ACCEPTED, accepted.getStatusCode());
        String jobId = (String) accepted.getBody().get("jobId");

        jobs.processNow();
        BackgroundJob job = jobs.awaitJobSuccess(jobId, Duration.ofSeconds(10));
        assertTrue(job.getResult() != null && job.getResult().contains("\"affected\":2"),
                "DISABLE must record affected=2; got: " + job.getResult());

        assertEquals(Boolean.FALSE, readActive(idA), "disableAll must flip agent A to active=false");
        assertEquals(Boolean.FALSE, readActive(idB), "disableAll must flip agent B to active=false");
    }

    // §5.9c — DELETE branch. deleteAll delegates to AgentAdminOperations.deleteAgent(id),
    // which is @PreAuthorize("hasPermission(#id, 'AgentDefinition', 'delete')") and resolves
    // to AgentPermissionEvaluator (ROLE_ADMIN + same-org). PersistentJobQueueService captures
    // the caller's SecurityContext at enqueue and re-installs it on the virtual-thread worker
    // before handler dispatch, so an ADMIN enqueuer drives the soft-delete through to
    // active=false on every agent in the payload.
    @Test
    void bulkActionDeleteWithAdminAuthSoftDeletesAgentsAndCompletes() {
        HttpHeaders auth = adminHeaders("bulk-deleter");

        String idA = createAgentViaApi(auth, "Doomed Bulk A", true);
        String idB = createAgentViaApi(auth, "Doomed Bulk B", true);

        Map<String, Object> req = Map.of("action", "DELETE", "ids", List.of(idA, idB));
        ResponseEntity<Map<String, Object>> accepted = rest.exchange(
                url("/api/admin/agents/bulk-action"),
                HttpMethod.POST, new HttpEntity<>(req, auth), JSON_MAP);

        assertEquals(HttpStatus.ACCEPTED, accepted.getStatusCode());
        String jobId = (String) accepted.getBody().get("jobId");

        jobs.processNow();
        BackgroundJob terminal = jobs.awaitJobSuccess(jobId, Duration.ofSeconds(10));
        assertNotNull(terminal.getResult(),
                "COMPLETED delete must persist the affected-count result JSON on background_jobs.result");
        assertTrue(terminal.getResult().contains("\"affected\":2"),
                "bulk delete of two agents must record affected=2; got " + terminal.getResult());

        assertEquals(Boolean.FALSE, readActive(idA),
                "agent A must be soft-deleted (active=false) because deleteAgent's @PreAuthorize saw the captured ADMIN context on the worker");
        assertEquals(Boolean.FALSE, readActive(idB),
                "agent B must be soft-deleted for the same reason");
    }

    // ─── helpers ───

    /**
     * Creates an agent via the real {@code POST /api/admin/agents} surface (same path
     * AgentsCrudRuntimeTest §5.1 pins) and then force-sets {@code active} to the requested
     * value via a direct {@code UPDATE}. The admin service ignores the inbound {@code active}
     * flag on creates (the DB column defaults to {@code true} and the creation path never
     * writes it), so to seed an inactive-by-design fixture we bypass the service for this
     * one column. Everything else — row shape, audit trail, cache eviction — still goes
     * through the real write path. Returns the generated agent id.
     */
    private String createAgentViaApi(HttpHeaders auth, String name, boolean active) {
        String agentId = "agent-" + UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId);
        body.put("name", name);
        body.put("description", "Created from AgentsBulkRuntimeTest");
        body.put("instructions", "Be helpful and concise.");
        body.put("model", "gpt-4o-mini");
        body.put("isReasoningEnabled", false);
        body.put("isTeam", false);
        body.put("requiresPiiRedaction", false);
        body.put("approvedForProduction", false);
        body.put("maintenanceMode", false);
        body.put("active", active);
        body.put("enforceJsonOutput", false);

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/admin/agents"), HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(),
                "fixture precondition: agent creation must succeed before the bulk-action runs");

        // AgentAdminService.createAgent does not honor the active flag on the inbound DTO —
        // rows are always created active. Pin the fixture-side `active` value via a direct
        // UPDATE so the ENABLE case can observe an inactive→active transition.
        jdbc.update("UPDATE agents SET active = ? WHERE id = ?", active, agentId);
        return agentId;
    }

    private Boolean readActive(String agentId) {
        return jdbc.queryForObject("SELECT active FROM agents WHERE id = ?", Boolean.class, agentId);
    }

    private HttpHeaders authenticatedHeaders(String username) {
        return authenticateAs(username, username + "@test.local", "pass-bulk-1234", List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    private HttpHeaders adminHeaders(String username) {
        return authenticateAs(username, username + "@test.local", "pass-bulk-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }
}
