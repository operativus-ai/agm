package ai.operativus.agentmanager.integration.extensions;

import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
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
 * Domain Responsibility: Focused authz pin for the four admin-gated endpoints on
 *   {@link ai.operativus.agentmanager.control.controller.ExtensionController}. Closes
 *   4 TODO entries on {@link ai.operativus.agentmanager.arch.AdminEndpointCoverageArchTest}.
 *
 *   <p>Endpoints in scope (all method-level {@code @PreAuthorize("hasRole('ADMIN')")}):
 *   <ul>
 *     <li>{@code POST   /api/v1/extensions}                  registerExtension</li>
 *     <li>{@code PUT    /api/v1/extensions/&#123;id&#125;}   updateExtension</li>
 *     <li>{@code DELETE /api/v1/extensions/&#123;id&#125;}   deleteExtension</li>
 *     <li>{@code POST   /api/v1/extensions/validate}         validateConnection</li>
 *   </ul>
 *
 *   <p>Each endpoint matrix: anonymous → 401 (JWT filter), ROLE_USER → 403
 *   (@PreAuthorize gate), ROLE_ADMIN → 2xx (gate clears + handler executes).
 *   The read-side endpoint ({@code GET /api/v1/extensions}, getExtensions) has no
 *   admin gate and is reachable by any authenticated principal — not in scope here.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class ExtensionAdminAuthzRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    // ─── POST /api/v1/extensions (registerExtension) ────────────────────────

    @Test
    void registerExtension_unauthenticated_returns401() {
        assertEquals(HttpStatus.UNAUTHORIZED, postNoAuth("/api/v1/extensions",
                bodyOf("anon")).getStatusCode());
    }

    @Test
    void registerExtension_roleUser_returns403() {
        HttpHeaders userAuth = userHeaders("e1-reg-user");
        assertEquals(HttpStatus.FORBIDDEN, post("/api/v1/extensions",
                bodyOf("user"), userAuth).getStatusCode());
    }

    @Test
    void registerExtension_roleAdmin_returns200_andHandlerExecutes() {
        HttpHeaders adminAuth = adminHeaders("e1-reg-admin");
        ResponseEntity<Map<String, Object>> resp = postJson("/api/v1/extensions",
                bodyOf("admin"), adminAuth);
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "admin POST must return 200; got " + resp.getStatusCode());
        assertNotNull(resp.getBody().get("id"), "created extension must echo its id");
    }

    // ─── PUT /api/v1/extensions/{id} (updateExtension) ──────────────────────

    @Test
    void updateExtension_unauthenticated_returns401() {
        assertEquals(HttpStatus.UNAUTHORIZED, exchangeNoAuth("/api/v1/extensions/" + UUID.randomUUID(),
                HttpMethod.PUT, bodyOf("anon")).getStatusCode());
    }

    @Test
    void updateExtension_roleUser_returns403() {
        HttpHeaders userAuth = userHeaders("e1-upd-user");
        assertEquals(HttpStatus.FORBIDDEN, exchange("/api/v1/extensions/" + UUID.randomUUID(),
                HttpMethod.PUT, bodyOf("user"), userAuth).getStatusCode());
    }

    @Test
    void updateExtension_roleAdmin_clearsGate() {
        HttpHeaders adminAuth = adminHeaders("e1-upd-admin");
        // Missing id surfaces as 404 (controller throws ResponseStatusException); the
        // contract under test is the gate, not the lookup behavior.
        ResponseEntity<String> resp = exchange("/api/v1/extensions/" + UUID.randomUUID(),
                HttpMethod.PUT, bodyOf("admin"), adminAuth);
        assertTrue(resp.getStatusCode() != HttpStatus.UNAUTHORIZED
                        && resp.getStatusCode() != HttpStatus.FORBIDDEN,
                "admin PUT must clear the gate (404/400 acceptable for the missing-id case); "
                        + "got " + resp.getStatusCode());
    }

    // ─── DELETE /api/v1/extensions/{id} (deleteExtension) ───────────────────

    @Test
    void deleteExtension_unauthenticated_returns401() {
        assertEquals(HttpStatus.UNAUTHORIZED, exchangeNoAuth("/api/v1/extensions/" + UUID.randomUUID(),
                HttpMethod.DELETE, null).getStatusCode());
    }

    @Test
    void deleteExtension_roleUser_returns403() {
        HttpHeaders userAuth = userHeaders("e1-del-user");
        assertEquals(HttpStatus.FORBIDDEN, exchange("/api/v1/extensions/" + UUID.randomUUID(),
                HttpMethod.DELETE, null, userAuth).getStatusCode());
    }

    @Test
    void deleteExtension_roleAdmin_returns204() {
        HttpHeaders adminAuth = adminHeaders("e1-del-admin");
        // Service short-circuits on missing id; returns 204 regardless. Admin gate is
        // the contract under test.
        ResponseEntity<Void> resp = rest.exchange(url("/api/v1/extensions/" + UUID.randomUUID()),
                HttpMethod.DELETE, new HttpEntity<>(adminAuth), Void.class);
        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode());
    }

    // ─── POST /api/v1/extensions/validate (validateConnection) ──────────────

    @Test
    void validateConnection_unauthenticated_returns401() {
        assertEquals(HttpStatus.UNAUTHORIZED, postNoAuth("/api/v1/extensions/validate",
                bodyOf("anon")).getStatusCode());
    }

    @Test
    void validateConnection_roleUser_returns403() {
        HttpHeaders userAuth = userHeaders("e1-val-user");
        assertEquals(HttpStatus.FORBIDDEN, post("/api/v1/extensions/validate",
                bodyOf("user"), userAuth).getStatusCode());
    }

    @Test
    void validateConnection_roleAdmin_returns200_withValidationResponse() {
        HttpHeaders adminAuth = adminHeaders("e1-val-admin");
        ResponseEntity<Map<String, Object>> resp = postJson("/api/v1/extensions/validate",
                bodyOf("admin"), adminAuth);
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "admin POST must return 200; got " + resp.getStatusCode());
        // Body is ValidationResponseDTO — carries success boolean + message + latencyMs.
        assertNotNull(resp.getBody().get("success"),
                "validation response must carry the 'success' field; got: " + resp.getBody());
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private Map<String, Object> bodyOf(String label) {
        Map<String, Object> body = new HashMap<>();
        body.put("id", "ext-e1-" + label + "-" + UUID.randomUUID());
        body.put("name", "Extension " + label);
        body.put("type", "WEBHOOK");
        body.put("url", "https://example.com/" + label);
        body.put("description", "authz probe");
        body.put("active", true);
        return body;
    }

    private HttpHeaders userHeaders(String label) {
        String tagged = label + "-" + UUID.randomUUID().toString().substring(0, 8);
        return authenticateAs(tagged, tagged + "@test.local", "pwd-e1-1234",
                List.of("ROLE_USER"));
    }

    private HttpHeaders adminHeaders(String label) {
        String tagged = label + "-" + UUID.randomUUID().toString().substring(0, 8);
        return authenticateAs(tagged, tagged + "@test.local", "pwd-e1-admin-1234",
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
