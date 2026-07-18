package com.operativus.agentmanager.integration.alerting;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Focused authz pin for the five admin-gated endpoints across the
 *   sibling alerting controllers
 *   {@link com.operativus.agentmanager.control.controller.AlertingController} and
 *   {@link com.operativus.agentmanager.control.controller.AlertIntegrationController}.
 *   Closes 5 TODO entries on
 *   {@link com.operativus.agentmanager.arch.AdminEndpointCoverageArchTest}.
 *
 *   <p>Endpoints in scope (all method-level {@code @PreAuthorize("hasRole('ADMIN')")}):
 *   <ul>
 *     <li>{@code POST   /api/alerts/rules}                              createRule</li>
 *     <li>{@code PUT    /api/alerts/rules/&#123;id&#125;}               updateRule</li>
 *     <li>{@code DELETE /api/alerts/rules/&#123;id&#125;}               deleteRule</li>
 *     <li>{@code POST   /api/alerts/events/&#123;id&#125;/acknowledge}  acknowledgeAlert</li>
 *     <li>{@code POST   /api/alerts/integrations/&#123;id&#125;/test}   testFire</li>
 *   </ul>
 *
 *   <p>Each endpoint matrix: anonymous → 401, ROLE_USER → 403, ROLE_ADMIN → gate-cleared
 *   (any non-401/non-403 outcome — service-level validation / missing-id can legitimately
 *   surface as 400/404 after the gate clears).
 *
 *   <p>The non-admin endpoints on these controllers (rule reads, event reads, integration
 *   list/CRUD) are reachable by any authenticated principal by design — not in scope.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class AlertingAdminAuthzRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    // ─── POST /api/alerts/rules (createRule) ────────────────────────────────

    @Test
    void createRule_unauthenticated_returns401() {
        assertEquals(HttpStatus.UNAUTHORIZED, postNoAuth("/api/alerts/rules",
                ruleBody("anon")).getStatusCode());
    }

    @Test
    void createRule_roleUser_returns403() {
        HttpHeaders userAuth = userHeaders("a1-cr-user");
        assertEquals(HttpStatus.FORBIDDEN, post("/api/alerts/rules",
                ruleBody("user"), userAuth).getStatusCode());
    }

    @Test
    void createRule_roleAdmin_clearsGate() {
        HttpHeaders adminAuth = adminHeaders("a1-cr-admin");
        ResponseEntity<String> resp = post("/api/alerts/rules",
                ruleBody("admin"), adminAuth);
        assertTrue(resp.getStatusCode() != HttpStatus.UNAUTHORIZED
                        && resp.getStatusCode() != HttpStatus.FORBIDDEN,
                "admin POST must clear the gate; got " + resp.getStatusCode());
    }

    // ─── PUT /api/alerts/rules/{id} (updateRule) ────────────────────────────

    @Test
    void updateRule_unauthenticated_returns401() {
        assertEquals(HttpStatus.UNAUTHORIZED, exchangeNoAuth(
                "/api/alerts/rules/" + UUID.randomUUID(), HttpMethod.PUT,
                ruleBody("anon")).getStatusCode());
    }

    @Test
    void updateRule_roleUser_returns403() {
        HttpHeaders userAuth = userHeaders("a1-ur-user");
        assertEquals(HttpStatus.FORBIDDEN, exchange(
                "/api/alerts/rules/" + UUID.randomUUID(), HttpMethod.PUT,
                ruleBody("user"), userAuth).getStatusCode());
    }

    @Test
    void updateRule_roleAdmin_clearsGate() {
        HttpHeaders adminAuth = adminHeaders("a1-ur-admin");
        ResponseEntity<String> resp = exchange(
                "/api/alerts/rules/" + UUID.randomUUID(), HttpMethod.PUT,
                ruleBody("admin"), adminAuth);
        assertTrue(resp.getStatusCode() != HttpStatus.UNAUTHORIZED
                        && resp.getStatusCode() != HttpStatus.FORBIDDEN,
                "admin PUT must clear the gate; got " + resp.getStatusCode());
    }

    // ─── DELETE /api/alerts/rules/{id} (deleteRule) ─────────────────────────

    @Test
    void deleteRule_unauthenticated_returns401() {
        assertEquals(HttpStatus.UNAUTHORIZED, exchangeNoAuth(
                "/api/alerts/rules/" + UUID.randomUUID(), HttpMethod.DELETE, null).getStatusCode());
    }

    @Test
    void deleteRule_roleUser_returns403() {
        HttpHeaders userAuth = userHeaders("a1-dr-user");
        assertEquals(HttpStatus.FORBIDDEN, exchange(
                "/api/alerts/rules/" + UUID.randomUUID(), HttpMethod.DELETE, null, userAuth).getStatusCode());
    }

    @Test
    void deleteRule_roleAdmin_clearsGate() {
        HttpHeaders adminAuth = adminHeaders("a1-dr-admin");
        ResponseEntity<String> resp = exchange(
                "/api/alerts/rules/" + UUID.randomUUID(), HttpMethod.DELETE, null, adminAuth);
        assertTrue(resp.getStatusCode() != HttpStatus.UNAUTHORIZED
                        && resp.getStatusCode() != HttpStatus.FORBIDDEN,
                "admin DELETE must clear the gate; got " + resp.getStatusCode());
    }

    // ─── POST /api/alerts/events/{id}/acknowledge (acknowledgeAlert) ────────

    @Test
    void acknowledgeAlert_unauthenticated_returns401() {
        assertEquals(HttpStatus.UNAUTHORIZED, postNoAuth(
                "/api/alerts/events/" + UUID.randomUUID() + "/acknowledge", Map.of()).getStatusCode());
    }

    @Test
    void acknowledgeAlert_roleUser_returns403() {
        HttpHeaders userAuth = userHeaders("a1-ack-user");
        assertEquals(HttpStatus.FORBIDDEN, post(
                "/api/alerts/events/" + UUID.randomUUID() + "/acknowledge", Map.of(), userAuth).getStatusCode());
    }

    @Test
    void acknowledgeAlert_roleAdmin_clearsGate() {
        HttpHeaders adminAuth = adminHeaders("a1-ack-admin");
        ResponseEntity<String> resp = post(
                "/api/alerts/events/" + UUID.randomUUID() + "/acknowledge", Map.of(), adminAuth);
        assertTrue(resp.getStatusCode() != HttpStatus.UNAUTHORIZED
                        && resp.getStatusCode() != HttpStatus.FORBIDDEN,
                "admin POST must clear the gate; got " + resp.getStatusCode());
    }

    // ─── POST /api/alerts/integrations/{id}/test (testFire) ─────────────────

    @Test
    void testFire_unauthenticated_returns401() {
        assertEquals(HttpStatus.UNAUTHORIZED, postNoAuth(
                "/api/alerts/integrations/" + UUID.randomUUID() + "/test", Map.of()).getStatusCode());
    }

    @Test
    void testFire_roleUser_returns403() {
        HttpHeaders userAuth = userHeaders("a1-tf-user");
        assertEquals(HttpStatus.FORBIDDEN, post(
                "/api/alerts/integrations/" + UUID.randomUUID() + "/test", Map.of(), userAuth).getStatusCode());
    }

    @Test
    void testFire_roleAdmin_clearsGate() {
        HttpHeaders adminAuth = adminHeaders("a1-tf-admin");
        ResponseEntity<String> resp = post(
                "/api/alerts/integrations/" + UUID.randomUUID() + "/test", Map.of(), adminAuth);
        assertTrue(resp.getStatusCode() != HttpStatus.UNAUTHORIZED
                        && resp.getStatusCode() != HttpStatus.FORBIDDEN,
                "admin POST must clear the gate; got " + resp.getStatusCode());
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    // Must satisfy AlertRuleRequest validation: a malformed body returns 400 at
    // @Valid argument resolution BEFORE @PreAuthorize runs, which would mask the
    // 403 these authz tests assert. Fields/enums track AlertRuleRequest exactly
    // (condition ∈ GT|GTE|LT|LTE|EQ, severity ∈ INFO|WARNING|CRITICAL).
    private Map<String, Object> ruleBody(String label) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "rule-a1-" + label + "-" + UUID.randomUUID());
        body.put("metricName", "agent.latency.p95");
        body.put("condition", "GT");
        body.put("threshold", 1000.0);
        body.put("windowSeconds", 60);
        body.put("severity", "WARNING");
        body.put("enabled", true);
        return body;
    }

    private HttpHeaders userHeaders(String label) {
        String tagged = label + "-" + UUID.randomUUID().toString().substring(0, 8);
        return authenticateAs(tagged, tagged + "@test.local", "pwd-a1-1234",
                List.of("ROLE_USER"));
    }

    private HttpHeaders adminHeaders(String label) {
        String tagged = label + "-" + UUID.randomUUID().toString().substring(0, 8);
        return authenticateAs(tagged, tagged + "@test.local", "pwd-a1-admin-1234",
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
