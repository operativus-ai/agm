package ai.operativus.agentmanager.integration.schedules;

import ai.operativus.agentmanager.core.model.AuthModels.JwtResponse;
import ai.operativus.agentmanager.core.model.AuthModels.LoginRequest;
import ai.operativus.agentmanager.core.model.AuthModels.RegisterRequest;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Tenant-isolation runtime coverage for the schedules surface.
 *   Mirrors the contract pinned by
 *   {@code KnowledgeBaseTenantIsolationRuntimeTest} for the schedule domain: ADMIN of
 *   org A cannot list, fetch, modify, delete, or trigger ADMIN of org B's schedules.
 *   Cross-tenant lookups return 404 / 204-no-mutation / empty-list as appropriate; no
 *   404-vs-403 distinction at the API surface (existence-leak protection).
 *   <p>
 *   Behavioral contracts (controller + service layer):
 *   <ul>
 *     <li>{@code GET /api/v1/schedules} — paginated; only own-org rows.</li>
 *     <li>{@code GET /api/v1/schedules/{id}} — 404 cross-tenant.</li>
 *     <li>{@code PUT /api/v1/schedules/{id}} — 404 cross-tenant; row not modified.</li>
 *     <li>{@code DELETE /api/v1/schedules/{id}} — returns 204 (controller is unconditional)
 *         BUT the row is NOT deleted cross-tenant (service-level guard).</li>
 *     <li>{@code GET /api/v1/schedules/{id}/runs} — empty list cross-tenant.</li>
 *     <li>{@code POST /api/v1/schedules/{id}/trigger} — accepted but never fires
 *         {@code triggerScheduleExecution} cross-tenant (poller-level guard).</li>
 *     <li>{@code POST /api/v1/schedules} — body-injected orgId is ignored;
 *         caller's org is stamped on the persisted row.</li>
 *   </ul>
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class ScheduleTenantIsolationRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @BeforeEach
    void resetState() {
        truncateDatabase();
        // agents.model_id FK target — schedules target an agent and ScheduleService
        // validates target ownership via agentRepository.existsByIdAndOrgId, so each
        // tenant needs a real agent row (which in turn needs a real model row).
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                """);
    }

    @Test
    void listReturnsOnlyCallerOrgSchedules() {
        HttpHeaders orgA = registerLoginWithOrg("sched-iso-a-list", "sched-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("sched-iso-b-list", "sched-iso-org-B");
        String aAgent = seedAgentForOrg("a-list", "sched-iso-org-A");
        String bAgent = seedAgentForOrg("b-list", "sched-iso-org-B");

        createSchedule(orgA, "A's Daily Report", aAgent);
        createSchedule(orgA, "A's Weekly Cleanup", aAgent);
        createSchedule(orgB, "B's Daily Sweep", bAgent);

        ResponseEntity<Map<String, Object>> aPage = rest.exchange(
                url("/api/v1/schedules"),
                HttpMethod.GET,
                new HttpEntity<>(orgA),
                JSON_MAP);
        assertEquals(HttpStatus.OK, aPage.getStatusCode());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> aContent = (List<Map<String, Object>>) aPage.getBody().get("content");
        assertEquals(2, aContent.size(),
                "org A listing must contain exactly A's 2 schedules; got " + aContent.size());
        assertTrue(aContent.stream().allMatch(r -> {
                    String name = String.valueOf(r.get("name"));
                    return name.startsWith("A's ");
                }),
                "every row in A's listing must be an A-named schedule; got " + aContent);

        ResponseEntity<Map<String, Object>> bPage = rest.exchange(
                url("/api/v1/schedules"),
                HttpMethod.GET,
                new HttpEntity<>(orgB),
                JSON_MAP);
        assertEquals(HttpStatus.OK, bPage.getStatusCode());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bContent = (List<Map<String, Object>>) bPage.getBody().get("content");
        assertEquals(1, bContent.size(),
                "org B listing must contain exactly B's 1 schedule; got " + bContent.size());
    }

    @Test
    void getById404ForCrossTenantSchedule() {
        HttpHeaders orgA = registerLoginWithOrg("sched-iso-a-get", "sched-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("sched-iso-b-get", "sched-iso-org-B");
        String bAgent = seedAgentForOrg("b-get", "sched-iso-org-B");

        String bId = createSchedule(orgB, "B's Get-Probe", bAgent);

        ResponseEntity<String> response = rest.exchange(
                url("/api/v1/schedules/" + bId),
                HttpMethod.GET,
                new HttpEntity<>(orgA),
                String.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "GET /schedules/{B-id} as A must return 404; got " + response.getStatusCode());
    }

    @Test
    void put404ForCrossTenantScheduleAndRowUnmodified() {
        HttpHeaders orgA = registerLoginWithOrg("sched-iso-a-put", "sched-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("sched-iso-b-put", "sched-iso-org-B");
        String aAgent = seedAgentForOrg("a-put", "sched-iso-org-A");
        String bAgent = seedAgentForOrg("b-put", "sched-iso-org-B");

        String bId = createSchedule(orgB, "B's Put-Probe", bAgent);

        // ScheduleService.updateSchedule validates target ownership BEFORE the cross-tenant
        // findByIdAndOrgId lookup. Use a real orgA-owned agent id so the body-validation
        // path passes and the 404 we assert comes from the cross-tenant find, not from a
        // false-positive 400 on the body.
        Map<String, Object> updateBody = new HashMap<>();
        updateBody.put("name", "should never apply");
        updateBody.put("cronExpression", "0 0 * * * *");
        updateBody.put("targetType", "AGENT");
        updateBody.put("targetId", aAgent);

        ResponseEntity<String> response = rest.exchange(
                url("/api/v1/schedules/" + bId),
                HttpMethod.PUT,
                new HttpEntity<>(updateBody, orgA),
                String.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "PUT cross-tenant must return 404; got " + response.getStatusCode());

        // Verify B's row was NOT modified.
        String bName = jdbc.queryForObject(
                "SELECT name FROM schedules WHERE id = ?",
                String.class, bId);
        assertEquals("B's Put-Probe", bName,
                "cross-tenant PUT must not have written B's row; got name=" + bName);
    }

    @Test
    void deleteCrossTenantScheduleIsNoOpAndPreservesRow() {
        HttpHeaders orgA = registerLoginWithOrg("sched-iso-a-del", "sched-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("sched-iso-b-del", "sched-iso-org-B");
        String bAgent = seedAgentForOrg("b-del", "sched-iso-org-B");

        String bId = createSchedule(orgB, "B's Delete-Probe", bAgent);

        // Controller returns 204 unconditionally; the service guards the actual delete.
        ResponseEntity<String> response = rest.exchange(
                url("/api/v1/schedules/" + bId),
                HttpMethod.DELETE,
                new HttpEntity<>(orgA),
                String.class);
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode(),
                "DELETE returns 204 unconditionally (controller shape preserved); got "
                        + response.getStatusCode());

        // Verify B's row still exists.
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM schedules WHERE id = ?",
                Long.class, bId);
        assertEquals(1L, count == null ? 0L : count,
                "cross-tenant DELETE must not have removed B's row");
    }

    @Test
    void getRunsReturnsEmptyForCrossTenantSchedule() {
        HttpHeaders orgA = registerLoginWithOrg("sched-iso-a-runs", "sched-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("sched-iso-b-runs", "sched-iso-org-B");
        String bAgent = seedAgentForOrg("b-runs", "sched-iso-org-B");

        String bId = createSchedule(orgB, "B's Runs-Probe", bAgent);

        // Direct-seed a run so the unscoped query would otherwise return one row.
        jdbc.update("""
                INSERT INTO schedule_runs
                  (id, schedule_id, status, started_at)
                VALUES (?, ?, 'COMPLETED', NOW())
                """,
                java.util.UUID.randomUUID().toString(), bId);

        // F4 — /runs returns Page<ScheduleRunDTO>; assert empty content + totalElements=0.
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/v1/schedules/" + bId + "/runs"),
                HttpMethod.GET,
                new HttpEntity<>(orgA),
                JSON_MAP);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) body.get("content");
        assertTrue(content == null || content.isEmpty(),
                "GET /schedules/{B-id}/runs as A must have empty content[] (parent invisible → runs invisible); got "
                        + content);
        // F4 wire shape: totalElements is nested under `page`
        // (spring.data.web.pageable.serialization-mode=direct, Spring Boot 4 / Spring Data 4).
        @SuppressWarnings("unchecked")
        Map<String, Object> pageMeta = (Map<String, Object>) body.get("page");
        assertEquals(0L, ((Number) pageMeta.get("totalElements")).longValue(),
                "cross-tenant /runs must also report totalElements=0");
    }

    @Test
    void triggerCrossTenantScheduleIsNoOpAndNoScheduleRunCreated() {
        HttpHeaders orgA = registerLoginWithOrg("sched-iso-a-trig", "sched-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("sched-iso-b-trig", "sched-iso-org-B");
        String bAgent = seedAgentForOrg("b-trig", "sched-iso-org-B");

        String bId = createSchedule(orgB, "B's Trigger-Probe", bAgent);

        // Pre-trigger run count for B's schedule (must remain unchanged after A's call).
        Long preTriggerRuns = jdbc.queryForObject(
                "SELECT COUNT(*) FROM schedule_runs WHERE schedule_id = ?",
                Long.class, bId);

        // A's admin attempts to trigger B's schedule. Controller's class-level @PreAuthorize
        // ('hasRole(ADMIN)') gate is cleared; the tenant guard lives in
        // ScheduleExecutionPoller.manualTrigger which uses findByIdAndOrgId — the cross-tenant
        // schedule resolves to Optional.empty so triggerScheduleExecution is never invoked.
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/v1/schedules/" + bId + "/trigger"),
                HttpMethod.POST,
                new HttpEntity<>(orgA),
                JSON_MAP);
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode(),
                "POST /trigger returns 202 unconditionally (controller shape preserved); got "
                        + response.getStatusCode());

        // Poller's manualTrigger spawns the run-record write via .start(() -> ...) onto a
        // virtual thread; give it a beat to commit (or NOT commit, in the cross-tenant
        // happy case) before asserting.
        try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

        Long postTriggerRuns = jdbc.queryForObject(
                "SELECT COUNT(*) FROM schedule_runs WHERE schedule_id = ?",
                Long.class, bId);
        assertEquals(preTriggerRuns, postTriggerRuns,
                "cross-tenant trigger must NOT create a schedule_runs row against B's schedule. "
                        + "Pre-fix manualTrigger would have used scheduleRepository.findById(id) and "
                        + "executed against B's tenant — leaking a job into another org's queue.");
    }

    @Test
    void postIgnoresBodyOrgIdAndStampsCallerOrgId() {
        HttpHeaders orgA = registerLoginWithOrg("sched-iso-a-post", "sched-iso-org-A");
        String aAgent = seedAgentForOrg("a-post", "sched-iso-org-A");

        Map<String, Object> body = new HashMap<>();
        body.put("name", "post-org-injection-attempt");
        body.put("description", "body claims org B");
        body.put("cronExpression", "0 0 8 * * *");
        body.put("targetType", "AGENT");
        body.put("targetId", aAgent);
        body.put("isActive", true);
        body.put("orgId", "sched-iso-org-B");

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/v1/schedules"),
                HttpMethod.POST,
                new HttpEntity<>(body, orgA),
                JSON_MAP);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        Map<String, Object> created = response.getBody();
        assertNotNull(created);

        String createdId = String.valueOf(created.get("id"));
        String storedOrgId = jdbc.queryForObject(
                "SELECT org_id FROM schedules WHERE id = ?",
                String.class, createdId);
        assertEquals("sched-iso-org-A", storedOrgId,
                "POST must stamp caller's orgId; body-injected orgId must be ignored. got=" + storedOrgId);
    }

    // ─── helpers ───

    private String createSchedule(HttpHeaders auth, String name, String targetId) {
        Map<String, Object> body = Map.of(
                "name", name,
                "description", "test fixture",
                "cronExpression", "0 0 8 * * *",
                "targetType", "AGENT",
                "targetId", targetId,
                "isActive", true);
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/v1/schedules"),
                HttpMethod.POST,
                new HttpEntity<>(body, auth),
                JSON_MAP);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode(),
                "createSchedule fixture must succeed; got " + resp.getStatusCode());
        return String.valueOf(resp.getBody().get("id"));
    }

    private String seedAgentForOrg(String label, String orgId) {
        String agentId = "agent-" + label + "-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, org_id, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, ?, now(), now())
                """, agentId, "Sched-iso Agent " + label, orgId);
        return agentId;
    }
}
