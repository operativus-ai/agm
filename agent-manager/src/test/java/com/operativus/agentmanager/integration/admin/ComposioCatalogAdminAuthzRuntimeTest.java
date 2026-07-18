package com.operativus.agentmanager.integration.admin;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Domain Responsibility: Focused authz pin for gap #21's two new endpoints on
 *   {@link com.operativus.agentmanager.control.controller.ComposioCatalogController}:
 *   <ul>
 *     <li>{@code GET  /api/admin/composio/catalog}          — listCatalog</li>
 *     <li>{@code POST /api/admin/composio/catalog/import}   — importApp</li>
 *   </ul>
 *
 *   <p>Pinned with the 4-case matrix (mirrors {@code ComposioConfigDriftAuthzRuntimeTest}):
 *   <ol>
 *     <li>Anonymous → 401</li>
 *     <li>ROLE_USER → 403</li>
 *     <li>ROLE_ADMIN → 403 (gate is SUPER_ADMIN, not ADMIN — pins privilege-
 *         escalation regressions if RoleHierarchyConfig is later flipped)</li>
 *     <li>ROLE_SUPER_ADMIN → non-401/non-403 (gate cleared; the handler may
 *         surface 2xx with an empty catalog body since the test profile has no
 *         Composio API key set — that's fine, the contract under test is the gate)</li>
 *   </ol>
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class ComposioCatalogAdminAuthzRuntimeTest extends BaseIntegrationTest {

    private static final String LIST_PATH = "/api/admin/composio/catalog";
    private static final String IMPORT_PATH = "/api/admin/composio/catalog/import";

    // ─── GET /catalog — listCatalog ──────────────────────────────────────────

    @Test
    void listCatalog_unauthenticated_returns401() {
        ResponseEntity<String> resp = rest.exchange(
                url(LIST_PATH), HttpMethod.GET,
                new HttpEntity<>(HttpHeaders.EMPTY), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
    }

    @Test
    void listCatalog_roleUser_returns403() {
        ResponseEntity<String> resp = rest.exchange(
                url(LIST_PATH), HttpMethod.GET,
                new HttpEntity<>(userHeaders("cat-user")), String.class);
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
    }

    @Test
    void listCatalog_roleAdmin_returns403_becauseGateIsSuperAdmin() {
        ResponseEntity<String> resp = rest.exchange(
                url(LIST_PATH), HttpMethod.GET,
                new HttpEntity<>(adminHeaders("cat-admin")), String.class);
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(),
                "ROLE_ADMIN must NOT clear SUPER_ADMIN gate — getting 2xx here would be a "
                        + "RoleHierarchyConfig regression. got " + resp.getStatusCode());
    }

    @Test
    void listCatalog_roleSuperAdmin_clearsGate() {
        ResponseEntity<String> resp = rest.exchange(
                url(LIST_PATH), HttpMethod.GET,
                new HttpEntity<>(superAdminHeaders("cat-super")), String.class);
        HttpStatusCode code = resp.getStatusCode();
        assertNotEquals(HttpStatus.UNAUTHORIZED, code, "401 means JWT filter rejected");
        assertNotEquals(HttpStatus.FORBIDDEN, code, "403 means SUPER_ADMIN gate refused — bug");
    }

    // ─── POST /catalog/import — importApp ────────────────────────────────────

    @Test
    void importApp_unauthenticated_returns401() {
        ResponseEntity<String> resp = rest.exchange(
                url(IMPORT_PATH), HttpMethod.POST,
                new HttpEntity<>(Map.of("app", "slack"), HttpHeaders.EMPTY),
                String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
    }

    @Test
    void importApp_roleUser_returns403() {
        ResponseEntity<String> resp = rest.exchange(
                url(IMPORT_PATH), HttpMethod.POST,
                new HttpEntity<>(Map.of("app", "slack"), userHeaders("imp-user")),
                String.class);
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
    }

    @Test
    void importApp_roleAdmin_returns403_becauseGateIsSuperAdmin() {
        ResponseEntity<String> resp = rest.exchange(
                url(IMPORT_PATH), HttpMethod.POST,
                new HttpEntity<>(Map.of("app", "slack"), adminHeaders("imp-admin")),
                String.class);
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
    }

    @Test
    void importApp_roleSuperAdmin_clearsGate() {
        ResponseEntity<String> resp = rest.exchange(
                url(IMPORT_PATH), HttpMethod.POST,
                new HttpEntity<>(Map.of("app", "slack"), superAdminHeaders("imp-super")),
                String.class);
        HttpStatusCode code = resp.getStatusCode();
        assertNotEquals(HttpStatus.UNAUTHORIZED, code);
        assertNotEquals(HttpStatus.FORBIDDEN, code);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private HttpHeaders userHeaders(String label) {
        String t = label + "-" + UUID.randomUUID().toString().substring(0, 8);
        return authenticateAs(t, t + "@t.local", "pwd-cat-1234", List.of("ROLE_USER"));
    }

    private HttpHeaders adminHeaders(String label) {
        String t = label + "-" + UUID.randomUUID().toString().substring(0, 8);
        return authenticateAs(t, t + "@t.local", "pwd-cat-1234", List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    private HttpHeaders superAdminHeaders(String label) {
        String t = label + "-" + UUID.randomUUID().toString().substring(0, 8);
        return authenticateAs(t, t + "@t.local", "pwd-cat-1234", List.of("ROLE_SUPER_ADMIN"));
    }
}
