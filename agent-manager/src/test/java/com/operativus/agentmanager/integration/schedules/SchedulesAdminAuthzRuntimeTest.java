package com.operativus.agentmanager.integration.schedules;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Focused authz pin for the four admin-gated mutating /
 *   trigger endpoints on {@link com.operativus.agentmanager.control.controller.SchedulesController}.
 *   Closes 4 TODO entries on
 *   {@link com.operativus.agentmanager.arch.AdminEndpointCoverageArchTest}.
 *
 *   <p>Endpoints in scope (all method-level {@code @PreAuthorize("hasRole('ADMIN')")}):
 *   <ul>
 *     <li>{@code POST   /api/v1/schedules}                          createSchedule</li>
 *     <li>{@code PUT    /api/v1/schedules/&#123;id&#125;}           updateSchedule</li>
 *     <li>{@code DELETE /api/v1/schedules/&#123;id&#125;}           deleteSchedule</li>
 *     <li>{@code POST   /api/v1/schedules/&#123;id&#125;/trigger}   triggerSchedule</li>
 *   </ul>
 *
 *   <p>Each endpoint matrix: anonymous → 401 (JWT filter), ROLE_USER → 403
 *   (@PreAuthorize gate), ROLE_ADMIN → 2xx. The class has no class-level
 *   @PreAuthorize, so non-admin-gated endpoints (GET list, GET /{id}, GET /{id}/runs)
 *   are reachable by any authenticated principal — those are NOT in scope here and
 *   are covered by {@code SchedulesRuntimeTest}.
 *
 *   <p>The 5th admin endpoint on this controller, {@code GET /batches}
 *   (getSpotBatches), is owned by {@code AdminEndpointAuthzRuntimeTest.ADMIN_ENDPOINTS}
 *   — tagged {@code matrix} in the coverage manifest.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class SchedulesAdminAuthzRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    // ─── POST /api/v1/schedules (createSchedule) ────────────────────────────

    @Test
    void createSchedule_unauthenticated_returns401() {
        assertEquals(HttpStatus.UNAUTHORIZED,
                postNoAuth("/api/v1/schedules", validBody("anon")).getStatusCode());
    }

    @Test
    void createSchedule_roleUser_returns403() {
        HttpHeaders userAuth = userHeaders("s1-cs-user");
        assertEquals(HttpStatus.FORBIDDEN,
                post("/api/v1/schedules", validBody("user"), userAuth).getStatusCode(),
                "ROLE_USER must hit the @PreAuthorize(\"hasRole('ADMIN')\") gate");
    }

    @Test
    void createSchedule_roleAdmin_clearsGate() {
        HttpHeaders adminAuth = adminHeaders("s1-cs-admin");
        // ScheduleService validates target FK + cron + dependency cycles. Our minimal-
        // valid body points at a nonexistent AGENT id, so the service throws
        // IllegalArgumentException -> 400. That's "gate cleared, handler rejected" —
        // the contract under test is the gate, not the validation.
        ResponseEntity<String> resp = post("/api/v1/schedules", validBody("admin"), adminAuth);
        assertTrue(resp.getStatusCode() != HttpStatus.UNAUTHORIZED
                        && resp.getStatusCode() != HttpStatus.FORBIDDEN,
                "admin POST must clear the @PreAuthorize gate (any non-401/403 outcome is fine); "
                        + "got " + resp.getStatusCode());
    }

    // ─── PUT /api/v1/schedules/{id} (updateSchedule) ────────────────────────

    @Test
    void updateSchedule_unauthenticated_returns401() {
        assertEquals(HttpStatus.UNAUTHORIZED, exchangeNoAuth(
                "/api/v1/schedules/" + UUID.randomUUID(), HttpMethod.PUT, validBody("anon")).getStatusCode());
    }

    @Test
    void updateSchedule_roleUser_returns403() {
        HttpHeaders userAuth = userHeaders("s1-us-user");
        assertEquals(HttpStatus.FORBIDDEN, exchange(
                "/api/v1/schedules/" + UUID.randomUUID(), HttpMethod.PUT, validBody("user"), userAuth).getStatusCode());
    }

    @Test
    void updateSchedule_roleAdmin_clearsGate() {
        HttpHeaders adminAuth = adminHeaders("s1-us-admin");
        // Missing schedule id may surface as 404 (service returns null → controller maps)
        // or 400 (service validates body's target FK before lookup). Either is "gate
        // cleared, handler rejected" — pin to non-401/non-403.
        ResponseEntity<String> resp = exchange(
                "/api/v1/schedules/" + UUID.randomUUID(), HttpMethod.PUT, validBody("admin"), adminAuth);
        assertTrue(resp.getStatusCode() != HttpStatus.UNAUTHORIZED
                        && resp.getStatusCode() != HttpStatus.FORBIDDEN,
                "admin PUT must clear the @PreAuthorize gate; got " + resp.getStatusCode());
    }

    // ─── DELETE /api/v1/schedules/{id} (deleteSchedule) ─────────────────────

    @Test
    void deleteSchedule_unauthenticated_returns401() {
        assertEquals(HttpStatus.UNAUTHORIZED, exchangeNoAuth(
                "/api/v1/schedules/" + UUID.randomUUID(), HttpMethod.DELETE, null).getStatusCode());
    }

    @Test
    void deleteSchedule_roleUser_returns403() {
        HttpHeaders userAuth = userHeaders("s1-ds-user");
        assertEquals(HttpStatus.FORBIDDEN, exchange(
                "/api/v1/schedules/" + UUID.randomUUID(), HttpMethod.DELETE, null, userAuth).getStatusCode());
    }

    @Test
    void deleteSchedule_roleAdmin_returns204() {
        HttpHeaders adminAuth = adminHeaders("s1-ds-admin");
        ResponseEntity<Void> resp = rest.exchange(
                url("/api/v1/schedules/" + UUID.randomUUID()), HttpMethod.DELETE,
                new HttpEntity<>(adminAuth), Void.class);
        // Service is no-op-on-miss; 204 even when the id does not resolve.
        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode());
    }

    // ─── POST /api/v1/schedules/{id}/trigger (triggerSchedule) ──────────────

    @Test
    void triggerSchedule_unauthenticated_returns401() {
        assertEquals(HttpStatus.UNAUTHORIZED, postNoAuth(
                "/api/v1/schedules/" + UUID.randomUUID() + "/trigger", Map.of()).getStatusCode());
    }

    @Test
    void triggerSchedule_roleUser_returns403() {
        HttpHeaders userAuth = userHeaders("s1-ts-user");
        assertEquals(HttpStatus.FORBIDDEN, post(
                "/api/v1/schedules/" + UUID.randomUUID() + "/trigger", Map.of(), userAuth).getStatusCode());
    }

    @Test
    void triggerSchedule_roleAdmin_returns202_withMessage() {
        HttpHeaders adminAuth = adminHeaders("s1-ts-admin");
        // manualTrigger is silent-no-op on unknown id (org-scoped findByIdAndOrgId returns
        // empty). The endpoint still returns 202 because the dispatch is async by design —
        // proven for the WORKFLOW target type by WorkflowScheduledExecutionRuntimeTest case 3.
        ResponseEntity<Map<String, Object>> resp = postJson(
                "/api/v1/schedules/" + UUID.randomUUID() + "/trigger", Map.of(), adminAuth);
        assertEquals(HttpStatus.ACCEPTED, resp.getStatusCode(),
                "admin POST must return 202; got " + resp.getStatusCode());
        assertEquals("Schedule execution triggered.", resp.getBody().get("message"));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private Map<String, Object> validBody(String label) {
        // Minimal-valid ScheduleDTO: cron + targetType + targetId. AGENT target_id can be a
        // random uuid string — createSchedule validates cron, but cross-org-agent validation
        // only fires when the agent actually exists; for missing agents the create succeeds
        // (the dispatch-time lookup is what would fail at trigger time).
        Map<String, Object> body = new HashMap<>();
        body.put("name", "s1-authz-" + label + "-" + UUID.randomUUID());
        body.put("cronExpression", "0 0 0 * * *");
        body.put("targetType", "AGENT");
        body.put("targetId", "nonexistent-agent-" + UUID.randomUUID());
        body.put("contextualPrompt", "authz probe");
        body.put("isActive", true);
        return body;
    }

    private HttpHeaders userHeaders(String label) {
        String tagged = label + "-" + UUID.randomUUID().toString().substring(0, 8);
        return authenticateAs(tagged, tagged + "@test.local", "pwd-s1-1234",
                List.of("ROLE_USER"));
    }

    private HttpHeaders adminHeaders(String label) {
        String tagged = label + "-" + UUID.randomUUID().toString().substring(0, 8);
        return authenticateAs(tagged, tagged + "@test.local", "pwd-s1-admin-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    private ResponseEntity<String> postNoAuth(String path, Object body) {
        return rest.exchange(url(path), HttpMethod.POST,
                new HttpEntity<>(body, HttpHeaders.EMPTY), String.class);
    }

    private ResponseEntity<String> post(String path, Object body, HttpHeaders auth) {
        return rest.exchange(url(path), HttpMethod.POST,
                new HttpEntity<>(body, auth), String.class);
    }

    private ResponseEntity<Map<String, Object>> postJson(String path, Object body, HttpHeaders auth) {
        return rest.exchange(url(path), HttpMethod.POST,
                new HttpEntity<>(body, auth), JSON_MAP);
    }

    private ResponseEntity<String> exchangeNoAuth(String path, HttpMethod method, Object body) {
        return rest.exchange(url(path), method,
                new HttpEntity<>(body, HttpHeaders.EMPTY), String.class);
    }

    private ResponseEntity<String> exchange(String path, HttpMethod method, Object body, HttpHeaders auth) {
        return rest.exchange(url(path), method,
                new HttpEntity<>(body, auth), String.class);
    }
}
