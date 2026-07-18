package ai.operativus.agentmanager.integration.security;

import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Bundle the last remaining admin-gated endpoints — singletons
 *   and 2-endpoint clusters across {@code ApprovalsController}, {@code IncidentResponseController},
 *   {@code SettingsController} — into a single focused authz pin. (The SLO and
 *   security-intercepts pins moved to the agm-enterprise repo with their controllers.)
 *   Closes 7 TODO entries on {@link ai.operativus.agentmanager.arch.AdminEndpointCoverageArchTest}.
 *
 *   <p>Each endpoint matrix: anonymous → 401, ROLE_USER → 403, ROLE_ADMIN → gate-cleared
 *   (any non-401/403 outcome; service-level validation / missing-id can legitimately
 *   surface as 400/404 after the gate clears).
 *
 *   <p>Endpoints in scope:
 *   <ul>
 *     <li>{@code POST   /api/v1/approvals/&#123;id&#125;/resolve}          resolveApproval</li>
 *     <li>{@code POST   /api/v1/approvals/bulk-resolve}                    bulkResolve</li>
 *     <li>{@code POST   /api/v1/admin/agents/&#123;agentId&#125;/quarantine}    quarantine</li>
 *     <li>{@code POST   /api/v1/admin/agents/&#123;agentId&#125;/unquarantine}  unquarantine</li>
 *     <li>{@code PUT    /api/v1/settings}                                  updateSettings</li>
 *     <li>{@code GET    /api/v1/observability/slo-status}                  getSloStatus</li>
 *     <li>{@code GET    /api/v1/observability/security-intercepts}         getCounts</li>
 *   </ul>
 *
 *   <p>The 3rd IncidentResponseController endpoint ({@code haltAllRuns}) is gated by
 *   {@code SUPER_ADMIN} (not {@code ADMIN}) and is owned by the
 *   {@code AdminEndpointAuthzRuntimeTest.SUPER_ADMIN_ENDPOINTS} matrix.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class MiscAdminAuthzRuntimeTest extends BaseIntegrationTest {

    // ─── POST /api/v1/approvals/{id}/resolve (resolveApproval) ──────────────

    @Test
    void resolveApproval_unauthenticated_returns401() {
        assertEquals(HttpStatus.UNAUTHORIZED, postNoAuth(
                "/api/v1/approvals/" + UUID.randomUUID() + "/resolve",
                Map.of("decision", "APPROVED")).getStatusCode());
    }

    @Test
    void resolveApproval_roleUser_returns403() {
        HttpHeaders userAuth = userHeaders("misc-ra-user");
        assertEquals(HttpStatus.FORBIDDEN, post(
                "/api/v1/approvals/" + UUID.randomUUID() + "/resolve",
                Map.of("decision", "APPROVED"), userAuth).getStatusCode());
    }

    @Test
    void resolveApproval_roleAdmin_clearsGate() {
        HttpHeaders adminAuth = adminHeaders("misc-ra-admin");
        ResponseEntity<String> resp = post(
                "/api/v1/approvals/" + UUID.randomUUID() + "/resolve",
                Map.of("decision", "APPROVED"), adminAuth);
        assertGateCleared(resp);
    }

    // ─── POST /api/v1/approvals/bulk-resolve (bulkResolve) ──────────────────

    @Test
    void bulkResolve_unauthenticated_returns401() {
        assertEquals(HttpStatus.UNAUTHORIZED, postNoAuth("/api/v1/approvals/bulk-resolve",
                Map.of("ids", List.of(), "decision", "APPROVED")).getStatusCode());
    }

    @Test
    void bulkResolve_roleUser_returns403() {
        HttpHeaders userAuth = userHeaders("misc-br-user");
        assertEquals(HttpStatus.FORBIDDEN, post("/api/v1/approvals/bulk-resolve",
                Map.of("ids", List.of(), "decision", "APPROVED"), userAuth).getStatusCode());
    }

    @Test
    void bulkResolve_roleAdmin_clearsGate() {
        HttpHeaders adminAuth = adminHeaders("misc-br-admin");
        ResponseEntity<String> resp = post("/api/v1/approvals/bulk-resolve",
                Map.of("ids", List.of(), "decision", "APPROVED"), adminAuth);
        assertGateCleared(resp);
    }

    // ─── POST /api/v1/admin/agents/{agentId}/quarantine (quarantine) ────────

    @Test
    void quarantine_unauthenticated_returns401() {
        assertEquals(HttpStatus.UNAUTHORIZED, postNoAuth(
                "/api/v1/admin/agents/" + UUID.randomUUID() + "/quarantine", Map.of("reason", "authz probe")).getStatusCode());
    }

    @Test
    void quarantine_roleUser_returns403() {
        HttpHeaders userAuth = userHeaders("misc-qu-user");
        assertEquals(HttpStatus.FORBIDDEN, post(
                "/api/v1/admin/agents/" + UUID.randomUUID() + "/quarantine", Map.of("reason", "authz probe"), userAuth).getStatusCode());
    }

    @Test
    void quarantine_roleAdmin_clearsGate() {
        HttpHeaders adminAuth = adminHeaders("misc-qu-admin");
        ResponseEntity<String> resp = post(
                "/api/v1/admin/agents/" + UUID.randomUUID() + "/quarantine", Map.of("reason", "authz probe"), adminAuth);
        assertGateCleared(resp);
    }

    // ─── POST /api/v1/admin/agents/{agentId}/unquarantine (unquarantine) ────

    @Test
    void unquarantine_unauthenticated_returns401() {
        assertEquals(HttpStatus.UNAUTHORIZED, postNoAuth(
                "/api/v1/admin/agents/" + UUID.randomUUID() + "/unquarantine", Map.of("reason", "authz probe")).getStatusCode());
    }

    @Test
    void unquarantine_roleUser_returns403() {
        HttpHeaders userAuth = userHeaders("misc-uq-user");
        assertEquals(HttpStatus.FORBIDDEN, post(
                "/api/v1/admin/agents/" + UUID.randomUUID() + "/unquarantine", Map.of("reason", "authz probe"), userAuth).getStatusCode());
    }

    @Test
    void unquarantine_roleAdmin_clearsGate() {
        HttpHeaders adminAuth = adminHeaders("misc-uq-admin");
        ResponseEntity<String> resp = post(
                "/api/v1/admin/agents/" + UUID.randomUUID() + "/unquarantine", Map.of("reason", "authz probe"), adminAuth);
        assertGateCleared(resp);
    }

    // ─── PUT /api/v1/settings (updateSettings) ──────────────────────────────

    @Test
    void updateSettings_unauthenticated_returns401() {
        assertEquals(HttpStatus.UNAUTHORIZED, exchangeNoAuth("/api/v1/settings",
                HttpMethod.PUT, Map.of("key", "value")).getStatusCode());
    }

    @Test
    void updateSettings_roleUser_returns403() {
        HttpHeaders userAuth = userHeaders("misc-us-user");
        assertEquals(HttpStatus.FORBIDDEN, exchange("/api/v1/settings",
                HttpMethod.PUT, Map.of("key", "value"), userAuth).getStatusCode());
    }

    @Test
    void updateSettings_roleAdmin_clearsGate() {
        HttpHeaders adminAuth = adminHeaders("misc-us-admin");
        ResponseEntity<String> resp = exchange("/api/v1/settings",
                HttpMethod.PUT, Map.of("key", "value"), adminAuth);
        assertGateCleared(resp);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private static void assertGateCleared(ResponseEntity<?> resp) {
        assertTrue(resp.getStatusCode() != HttpStatus.UNAUTHORIZED
                        && resp.getStatusCode() != HttpStatus.FORBIDDEN,
                "admin call must clear the @PreAuthorize gate (any non-401/403 outcome is "
                        + "acceptable — body validation / missing-id surfaces as 400/404 "
                        + "after the gate clears); got " + resp.getStatusCode());
    }

    private HttpHeaders userHeaders(String label) {
        String tagged = label + "-" + UUID.randomUUID().toString().substring(0, 8);
        return authenticateAs(tagged, tagged + "@test.local", "pwd-misc-1234",
                List.of("ROLE_USER"));
    }

    private HttpHeaders adminHeaders(String label) {
        String tagged = label + "-" + UUID.randomUUID().toString().substring(0, 8);
        return authenticateAs(tagged, tagged + "@test.local", "pwd-misc-admin-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    private ResponseEntity<String> getNoAuth(String path) {
        return rest.exchange(url(path), HttpMethod.GET,
                new HttpEntity<>(HttpHeaders.EMPTY), String.class);
    }

    private ResponseEntity<String> get(String path, HttpHeaders auth) {
        return rest.exchange(url(path), HttpMethod.GET,
                new HttpEntity<>(auth), String.class);
    }

    private ResponseEntity<String> postNoAuth(String path, Object body) {
        return rest.exchange(url(path), HttpMethod.POST,
                new HttpEntity<>(body, HttpHeaders.EMPTY), String.class);
    }

    private ResponseEntity<String> post(String path, Object body, HttpHeaders auth) {
        return rest.exchange(url(path), HttpMethod.POST,
                new HttpEntity<>(body, auth), String.class);
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
