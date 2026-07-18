package com.operativus.agentmanager.integration.evaluations;

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
 * Domain Responsibility: Focused authz pin for four admin-gated CRUD endpoints on
 *   {@link com.operativus.agentmanager.control.controller.EvaluationController}. Closes
 *   4 TODO entries on {@link com.operativus.agentmanager.arch.AdminEndpointCoverageArchTest}.
 *
 *   <p>Endpoints in scope (all method-level {@code @PreAuthorize("hasRole('ADMIN')")}):
 *   <ul>
 *     <li>{@code POST   /api/v1/evaluations/suites}                                createSuite</li>
 *     <li>{@code DELETE /api/v1/evaluations/suites/&#123;suiteId&#125;}            deleteSuite</li>
 *     <li>{@code POST   /api/v1/evaluations/suites/&#123;suiteId&#125;/cases}      addCaseToSuite</li>
 *     <li>{@code DELETE /api/v1/evaluations/cases/&#123;caseId&#125;}              deleteCase</li>
 *   </ul>
 *
 *   <p>The 5th admin endpoint on this controller ({@code POST /suites/&#123;suiteId&#125;/run}
 *   runSuite) is owned by {@code EvaluationControllerAuthzRuntimeTest} and tagged
 *   {@code focused:} in the arch manifest.
 *
 *   <p>Each endpoint matrix: anonymous → 401, ROLE_USER → 403, ROLE_ADMIN → gate-cleared
 *   (any non-401/403 outcome — body validation / missing-id can legitimately surface as
 *   400/404 after the gate clears).
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class EvaluationAdminAuthzRuntimeTest extends BaseIntegrationTest {

    // ─── POST /api/v1/evaluations/suites (createSuite) ──────────────────────

    @Test
    void createSuite_unauthenticated_returns401() {
        assertEquals(HttpStatus.UNAUTHORIZED, postNoAuth("/api/v1/evaluations/suites",
                Map.of("name", "anon-suite")).getStatusCode());
    }

    @Test
    void createSuite_roleUser_returns403() {
        HttpHeaders userAuth = userHeaders("ev1-cs-user");
        assertEquals(HttpStatus.FORBIDDEN, post("/api/v1/evaluations/suites",
                Map.of("name", "user-suite"), userAuth).getStatusCode());
    }

    @Test
    void createSuite_roleAdmin_clearsGate() {
        HttpHeaders adminAuth = adminHeaders("ev1-cs-admin");
        ResponseEntity<String> resp = post("/api/v1/evaluations/suites",
                Map.of("name", "admin-suite-" + UUID.randomUUID()), adminAuth);
        assertTrue(resp.getStatusCode() != HttpStatus.UNAUTHORIZED
                        && resp.getStatusCode() != HttpStatus.FORBIDDEN,
                "admin POST must clear the @PreAuthorize gate; got " + resp.getStatusCode());
    }

    // ─── DELETE /api/v1/evaluations/suites/{suiteId} (deleteSuite) ──────────

    @Test
    void deleteSuite_unauthenticated_returns401() {
        assertEquals(HttpStatus.UNAUTHORIZED, exchangeNoAuth(
                "/api/v1/evaluations/suites/" + UUID.randomUUID(), HttpMethod.DELETE, null).getStatusCode());
    }

    @Test
    void deleteSuite_roleUser_returns403() {
        HttpHeaders userAuth = userHeaders("ev1-ds-user");
        assertEquals(HttpStatus.FORBIDDEN, exchange(
                "/api/v1/evaluations/suites/" + UUID.randomUUID(), HttpMethod.DELETE, null, userAuth).getStatusCode());
    }

    @Test
    void deleteSuite_roleAdmin_clearsGate() {
        HttpHeaders adminAuth = adminHeaders("ev1-ds-admin");
        ResponseEntity<String> resp = exchange(
                "/api/v1/evaluations/suites/" + UUID.randomUUID(), HttpMethod.DELETE, null, adminAuth);
        assertTrue(resp.getStatusCode() != HttpStatus.UNAUTHORIZED
                        && resp.getStatusCode() != HttpStatus.FORBIDDEN,
                "admin DELETE must clear the gate; got " + resp.getStatusCode());
    }

    // ─── POST /api/v1/evaluations/suites/{suiteId}/cases (addCaseToSuite) ───

    @Test
    void addCaseToSuite_unauthenticated_returns401() {
        assertEquals(HttpStatus.UNAUTHORIZED, postNoAuth(
                "/api/v1/evaluations/suites/" + UUID.randomUUID() + "/cases",
                Map.of("name", "anon-case", "input", "anon-input")).getStatusCode());
    }

    @Test
    void addCaseToSuite_roleUser_returns403() {
        HttpHeaders userAuth = userHeaders("ev1-ac-user");
        assertEquals(HttpStatus.FORBIDDEN, post(
                "/api/v1/evaluations/suites/" + UUID.randomUUID() + "/cases",
                Map.of("name", "user-case", "input", "user-input"), userAuth).getStatusCode());
    }

    @Test
    void addCaseToSuite_roleAdmin_clearsGate() {
        HttpHeaders adminAuth = adminHeaders("ev1-ac-admin");
        ResponseEntity<String> resp = post(
                "/api/v1/evaluations/suites/" + UUID.randomUUID() + "/cases",
                Map.of("name", "admin-case-" + UUID.randomUUID(), "input", "admin-input"), adminAuth);
        assertTrue(resp.getStatusCode() != HttpStatus.UNAUTHORIZED
                        && resp.getStatusCode() != HttpStatus.FORBIDDEN,
                "admin POST must clear the gate; got " + resp.getStatusCode());
    }

    // ─── DELETE /api/v1/evaluations/cases/{caseId} (deleteCase) ─────────────

    @Test
    void deleteCase_unauthenticated_returns401() {
        assertEquals(HttpStatus.UNAUTHORIZED, exchangeNoAuth(
                "/api/v1/evaluations/cases/" + UUID.randomUUID(), HttpMethod.DELETE, null).getStatusCode());
    }

    @Test
    void deleteCase_roleUser_returns403() {
        HttpHeaders userAuth = userHeaders("ev1-dc-user");
        assertEquals(HttpStatus.FORBIDDEN, exchange(
                "/api/v1/evaluations/cases/" + UUID.randomUUID(), HttpMethod.DELETE, null, userAuth).getStatusCode());
    }

    @Test
    void deleteCase_roleAdmin_clearsGate() {
        HttpHeaders adminAuth = adminHeaders("ev1-dc-admin");
        ResponseEntity<String> resp = exchange(
                "/api/v1/evaluations/cases/" + UUID.randomUUID(), HttpMethod.DELETE, null, adminAuth);
        assertTrue(resp.getStatusCode() != HttpStatus.UNAUTHORIZED
                        && resp.getStatusCode() != HttpStatus.FORBIDDEN,
                "admin DELETE must clear the gate; got " + resp.getStatusCode());
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private HttpHeaders userHeaders(String label) {
        String tagged = label + "-" + UUID.randomUUID().toString().substring(0, 8);
        return authenticateAs(tagged, tagged + "@test.local", "pwd-ev1-1234",
                List.of("ROLE_USER"));
    }

    private HttpHeaders adminHeaders(String label) {
        String tagged = label + "-" + UUID.randomUUID().toString().substring(0, 8);
        return authenticateAs(tagged, tagged + "@test.local", "pwd-ev1-admin-1234",
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

    private ResponseEntity<String> exchangeNoAuth(String path, HttpMethod method, Object body) {
        return rest.exchange(url(path), method,
                new HttpEntity<>(body, HttpHeaders.EMPTY), String.class);
    }

    private ResponseEntity<String> exchange(String path, HttpMethod method, Object body, HttpHeaders auth) {
        return rest.exchange(url(path), method,
                new HttpEntity<>(body, auth), String.class);
    }
}
