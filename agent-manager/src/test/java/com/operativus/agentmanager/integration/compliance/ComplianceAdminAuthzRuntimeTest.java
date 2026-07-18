package com.operativus.agentmanager.integration.compliance;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
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
 * Domain Responsibility: Focused authz pin for the four admin-gated endpoints on
 *   {@link com.operativus.agentmanager.control.controller.ComplianceController}. Closes
 *   4 TODO entries on {@link com.operativus.agentmanager.arch.AdminEndpointCoverageArchTest}.
 *
 *   <p>Endpoints in scope:
 *   <ul>
 *     <li>{@code GET    /api/compliance/export/&#123;userId&#125;}  exportUserData —
 *         compound gate {@code "hasRole('ADMIN') or #userId == authentication.name"}.
 *         A ROLE_USER caller CAN export their OWN data (self-match clears the gate);
 *         that exception is pinned here as well.</li>
 *     <li>{@code POST   /api/compliance/erasure-requests}           submitErasureRequest</li>
 *     <li>{@code GET    /api/compliance/erasure-requests}           listErasureRequests</li>
 *     <li>{@code DELETE /api/compliance/erase/&#123;userId&#125;}   eraseUserData</li>
 *   </ul>
 *
 *   <p>Each endpoint matrix: anonymous → 401, ROLE_USER → 403, ROLE_ADMIN → gate-cleared.
 *   For {@code exportUserData}, an extra self-data case proves the {@code #userId ==
 *   authentication.name} SpEL branch fires for non-admin self-targeting callers.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class ComplianceAdminAuthzRuntimeTest extends BaseIntegrationTest {

    // ─── GET /api/compliance/export/{userId} (exportUserData) ───────────────

    @Test
    void exportUserData_unauthenticated_returns401() {
        assertEquals(HttpStatus.UNAUTHORIZED, getNoAuth(
                "/api/compliance/export/" + UUID.randomUUID()).getStatusCode());
    }

    @Test
    void exportUserData_roleUserTargetingOtherUser_returns403() {
        HttpHeaders userAuth = userHeaders("cp1-ex-user");
        // Caller is some random user; target is a different user id → fails both branches
        // of "hasRole('ADMIN') or #userId == authentication.name".
        assertEquals(HttpStatus.FORBIDDEN, get(
                "/api/compliance/export/" + UUID.randomUUID(), userAuth).getStatusCode(),
                "ROLE_USER targeting a DIFFERENT userId must hit the @PreAuthorize gate");
    }

    @Test
    void exportUserData_roleUserTargetingOwnUsername_clearsGate() {
        String selfUsername = "cp1-ex-self-" + UUID.randomUUID().toString().substring(0, 8);
        HttpHeaders selfAuth = authenticateAs(selfUsername, selfUsername + "@test.local",
                "pwd-cp1-1234", List.of("ROLE_USER"));
        // Self-targeting matches the SpEL #userId == authentication.name branch.
        ResponseEntity<String> resp = get("/api/compliance/export/" + selfUsername, selfAuth);
        assertTrue(resp.getStatusCode() != HttpStatus.UNAUTHORIZED
                        && resp.getStatusCode() != HttpStatus.FORBIDDEN,
                "ROLE_USER targeting their OWN userId must clear the gate via the SpEL "
                        + "self-match branch; got " + resp.getStatusCode());
    }

    @Test
    void exportUserData_roleAdmin_clearsGate() {
        HttpHeaders adminAuth = adminHeaders("cp1-ex-admin");
        ResponseEntity<String> resp = get(
                "/api/compliance/export/" + UUID.randomUUID(), adminAuth);
        assertTrue(resp.getStatusCode() != HttpStatus.UNAUTHORIZED
                        && resp.getStatusCode() != HttpStatus.FORBIDDEN,
                "ROLE_ADMIN must clear the gate regardless of target userId; got "
                        + resp.getStatusCode());
    }

    // ─── POST /api/compliance/erasure-requests (submitErasureRequest) ───────

    @Test
    void submitErasureRequest_unauthenticated_returns401() {
        assertEquals(HttpStatus.UNAUTHORIZED, postNoAuth(
                "/api/compliance/erasure-requests?userId=" + UUID.randomUUID(), Map.of()).getStatusCode());
    }

    @Test
    void submitErasureRequest_roleUser_returns403() {
        HttpHeaders userAuth = userHeaders("cp1-sub-user");
        assertEquals(HttpStatus.FORBIDDEN, post(
                "/api/compliance/erasure-requests?userId=" + UUID.randomUUID(), Map.of(), userAuth).getStatusCode());
    }

    @Test
    void submitErasureRequest_roleAdmin_clearsGate() {
        HttpHeaders adminAuth = adminHeaders("cp1-sub-admin");
        ResponseEntity<String> resp = post(
                "/api/compliance/erasure-requests?userId=" + UUID.randomUUID(), Map.of(), adminAuth);
        assertTrue(resp.getStatusCode() != HttpStatus.UNAUTHORIZED
                        && resp.getStatusCode() != HttpStatus.FORBIDDEN,
                "admin POST must clear the gate; got " + resp.getStatusCode());
    }

    // ─── GET /api/compliance/erasure-requests (listErasureRequests) ─────────

    @Test
    void listErasureRequests_unauthenticated_returns401() {
        assertEquals(HttpStatus.UNAUTHORIZED, getNoAuth(
                "/api/compliance/erasure-requests?userId=" + UUID.randomUUID()).getStatusCode());
    }

    @Test
    void listErasureRequests_roleUser_returns403() {
        HttpHeaders userAuth = userHeaders("cp1-list-user");
        assertEquals(HttpStatus.FORBIDDEN, get(
                "/api/compliance/erasure-requests?userId=" + UUID.randomUUID(), userAuth).getStatusCode());
    }

    @Test
    void listErasureRequests_roleAdmin_clearsGate() {
        HttpHeaders adminAuth = adminHeaders("cp1-list-admin");
        ResponseEntity<String> resp = get(
                "/api/compliance/erasure-requests?userId=" + UUID.randomUUID(), adminAuth);
        assertTrue(resp.getStatusCode() != HttpStatus.UNAUTHORIZED
                        && resp.getStatusCode() != HttpStatus.FORBIDDEN,
                "admin GET must clear the gate; got " + resp.getStatusCode());
    }

    // ─── DELETE /api/compliance/erase/{userId} (eraseUserData) ──────────────

    @Test
    void eraseUserData_unauthenticated_returns401() {
        assertEquals(HttpStatus.UNAUTHORIZED, exchangeNoAuth(
                "/api/compliance/erase/" + UUID.randomUUID(), HttpMethod.DELETE, null).getStatusCode());
    }

    @Test
    void eraseUserData_roleUser_returns403() {
        HttpHeaders userAuth = userHeaders("cp1-er-user");
        assertEquals(HttpStatus.FORBIDDEN, exchange(
                "/api/compliance/erase/" + UUID.randomUUID(), HttpMethod.DELETE, null, userAuth).getStatusCode());
    }

    @Test
    void eraseUserData_roleAdmin_clearsGate() {
        HttpHeaders adminAuth = adminHeaders("cp1-er-admin");
        ResponseEntity<String> resp = exchange(
                "/api/compliance/erase/" + UUID.randomUUID(), HttpMethod.DELETE, null, adminAuth);
        assertTrue(resp.getStatusCode() != HttpStatus.UNAUTHORIZED
                        && resp.getStatusCode() != HttpStatus.FORBIDDEN,
                "admin DELETE must clear the gate; got " + resp.getStatusCode());
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private HttpHeaders userHeaders(String label) {
        String tagged = label + "-" + UUID.randomUUID().toString().substring(0, 8);
        return authenticateAs(tagged, tagged + "@test.local", "pwd-cp1-1234",
                List.of("ROLE_USER"));
    }

    private HttpHeaders adminHeaders(String label) {
        String tagged = label + "-" + UUID.randomUUID().toString().substring(0, 8);
        return authenticateAs(tagged, tagged + "@test.local", "pwd-cp1-admin-1234",
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
