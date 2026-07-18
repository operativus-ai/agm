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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Domain Responsibility: Focused authz pin for the single super-admin-gated endpoint
 *   on {@link com.operativus.agentmanager.control.controller.ComposioConfigDriftController}:
 *   <ul>
 *     <li>{@code GET /api/admin/composio/config-drift} — getConfigDrift</li>
 *   </ul>
 *
 *   <p>Closes the only remaining {@code TODO: needs focused authz test} entry on
 *   {@code SuperAdminEndpointCoverageArchTest.SUPER_ADMIN_ENDPOINT_COVERAGE} —
 *   bumps that tag to {@code focused: ComposioConfigDriftAuthzRuntimeTest}.
 *
 *   <p>Matrix (4 cases; one more than the standard 3-case ADMIN pattern because
 *   {@code SUPER_ADMIN} is strictly above {@code ADMIN} in {@code RoleHierarchyConfig},
 *   so ROLE_ADMIN callers must explicitly fail the gate):
 *   <ol>
 *     <li>Anonymous request → 401 (rejected at JWT filter)</li>
 *     <li>ROLE_USER request → 403 (rejected at the SUPER_ADMIN gate)</li>
 *     <li>ROLE_ADMIN request → 403 (gate is SUPER_ADMIN, not ADMIN — proves the
 *         arch test's super-admin manifest matches a real authz step beyond ADMIN)</li>
 *     <li>ROLE_SUPER_ADMIN request → non-401/non-403 (gate cleared; the handler
 *         may surface a 5xx because the underlying Composio config-drift service has
 *         outbound dependencies that aren't wired in this test context — the contract
 *         under test is the gate, not the snapshot payload)</li>
 *   </ol>
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class ComposioConfigDriftAuthzRuntimeTest extends BaseIntegrationTest {

    private static final String PATH = "/api/admin/composio/config-drift";

    @Test
    void getConfigDrift_unauthenticated_returns401() {
        ResponseEntity<String> resp = rest.exchange(
                url(PATH), HttpMethod.GET,
                new HttpEntity<>(HttpHeaders.EMPTY), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode(),
                "anon GET must hit JWT filter; got " + resp.getStatusCode());
    }

    @Test
    void getConfigDrift_roleUser_returns403() {
        HttpHeaders userAuth = userHeaders("ccd-user");
        ResponseEntity<String> resp = rest.exchange(
                url(PATH), HttpMethod.GET,
                new HttpEntity<>(userAuth), String.class);
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(),
                "ROLE_USER GET must hit the @PreAuthorize(\"hasRole('SUPER_ADMIN')\") gate; "
                        + "got " + resp.getStatusCode());
    }

    @Test
    void getConfigDrift_roleAdmin_returns403_becauseGateIsSuperAdmin() {
        HttpHeaders adminAuth = adminHeaders("ccd-admin");
        ResponseEntity<String> resp = rest.exchange(
                url(PATH), HttpMethod.GET,
                new HttpEntity<>(adminAuth), String.class);
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(),
                "ROLE_ADMIN must NOT clear the SUPER_ADMIN gate — getting 2xx here would "
                        + "mean RoleHierarchyConfig flipped to ROLE_ADMIN >= ROLE_SUPER_ADMIN, "
                        + "a privilege-escalation regression; got " + resp.getStatusCode());
    }

    @Test
    void getConfigDrift_roleSuperAdmin_clearsGate() {
        HttpHeaders superAuth = superAdminHeaders("ccd-super");
        ResponseEntity<String> resp = rest.exchange(
                url(PATH), HttpMethod.GET,
                new HttpEntity<>(superAuth), String.class);
        HttpStatusCode code = resp.getStatusCode();
        assertNotEquals(HttpStatus.UNAUTHORIZED, code,
                "ROLE_SUPER_ADMIN must pass the JWT filter; got 401");
        assertNotEquals(HttpStatus.FORBIDDEN, code,
                "ROLE_SUPER_ADMIN must clear the @PreAuthorize(\"hasRole('SUPER_ADMIN')\") "
                        + "gate; got 403 means the gate is still refusing super-admins, which "
                        + "is the only contract under test in this PR");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private HttpHeaders userHeaders(String label) {
        String tagged = label + "-" + UUID.randomUUID().toString().substring(0, 8);
        return authenticateAs(tagged, tagged + "@test.local", "pwd-ccd-1234",
                List.of("ROLE_USER"));
    }

    private HttpHeaders adminHeaders(String label) {
        String tagged = label + "-" + UUID.randomUUID().toString().substring(0, 8);
        return authenticateAs(tagged, tagged + "@test.local", "pwd-ccd-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    private HttpHeaders superAdminHeaders(String label) {
        String tagged = label + "-" + UUID.randomUUID().toString().substring(0, 8);
        return authenticateAs(tagged, tagged + "@test.local", "pwd-ccd-1234",
                List.of("ROLE_SUPER_ADMIN"));
    }
}
