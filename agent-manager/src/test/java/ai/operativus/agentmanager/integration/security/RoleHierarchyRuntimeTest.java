package ai.operativus.agentmanager.integration.security;

import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Domain Responsibility: Pins {@code RoleHierarchyConfig} actually wires through Spring
 *   Security's method-level {@code @PreAuthorize} evaluator. The hierarchy declares
 *   {@code ROLE_SUPER_ADMIN > ROLE_ADMIN > ROLE_OPERATOR > ROLE_USER > ROLE_VIEWER} —
 *   but a config bean is just a config bean unless it's actually consumed by the security
 *   evaluator chain via
 *   {@code DefaultMethodSecurityExpressionHandler.setRoleHierarchy(...)}.
 *
 *   <p>The unique contract this test pins: a user holding ONLY {@code ROLE_SUPER_ADMIN} can
 *   access {@code @PreAuthorize("hasRole('ADMIN')")}-gated endpoints transitively. This is
 *   NOT covered by {@link AdminEndpointAuthzRuntimeTest}, which authenticates its admin
 *   user with {@code List.of("ROLE_USER", "ROLE_ADMIN")} (multi-role) and its super-admin
 *   user with {@code List.of("ROLE_SUPER_ADMIN")} but only exercises super-admin endpoints.
 *   The gap is the cross-tier case.
 *
 *   <p>Pinned cases:
 *   <ul>
 *     <li><b>SUPER_ADMIN passes ADMIN gate transitively</b> — hierarchy bean is wired
 *         into the method-level evaluator. If this fails, verify
 *         {@code SecurityConfig.methodSecurityExpressionHandler} calls
 *         {@code handler.setRoleHierarchy(roleHierarchy)}.</li>
 *     <li><b>ADMIN does NOT pass SUPER_ADMIN gate</b> — hierarchy is one-directional
 *         (upward inheritance only, no downward).</li>
 *     <li><b>USER does NOT pass ADMIN gate</b> — basic role isolation, smoke check.</li>
 *   </ul>
 *
 *   <p>Note: {@code @PreAuthorize("hasRole('OPERATOR'/'VIEWER'/'USER')")} is not currently
 *   used anywhere in the codebase, so transitivity at those tiers can't be exercised via
 *   live endpoints. The hierarchy bean declares them but they're aspirational at the
 *   evaluator level — only the ADMIN ⇄ SUPER_ADMIN edge is in active use.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class RoleHierarchyRuntimeTest extends BaseIntegrationTest {

    /**
     * An endpoint gated by {@code @PreAuthorize("hasRole('ADMIN')")} at the class level
     * (DataRetentionController). GET returns the retention policy map.
     */
    private static final String ADMIN_GATED_ENDPOINT = "/api/admin/retention/policies";

    /**
     * An endpoint gated by {@code @PreAuthorize("hasRole('SUPER_ADMIN')")} at the class
     * level (ComposioAdminController). GET lists Composio actions.
     */
    private static final String SUPER_ADMIN_GATED_ENDPOINT = "/api/admin/composio/actions";

    @BeforeEach
    void resetState() {
        truncateDatabase();
    }

    @Test
    void superAdminOnlyUserPassesAdminGatedEndpointViaRoleHierarchy() {
        HttpHeaders superOnlyAuth = authenticateAs("hierarchy-super-only",
                "hierarchy-super-only@test.local", "pass-hso-1234",
                List.of("ROLE_SUPER_ADMIN"));

        ResponseEntity<String> response = rest.exchange(
                url(ADMIN_GATED_ENDPOINT),
                HttpMethod.GET,
                new HttpEntity<>(superOnlyAuth),
                String.class);

        HttpStatusCode status = response.getStatusCode();
        assertNotEquals(HttpStatus.UNAUTHORIZED, status,
                "user with only ROLE_SUPER_ADMIN must NOT be rejected with 401 on an "
                        + "ADMIN-gated endpoint; got " + status);
        assertNotEquals(HttpStatus.FORBIDDEN, status,
                "user with only ROLE_SUPER_ADMIN must pass an ADMIN-gated endpoint via "
                        + "RoleHierarchyConfig (SUPER_ADMIN > ADMIN); got 403 — the role "
                        + "hierarchy bean is not wired through to the method-level evaluator. "
                        + "Verify SecurityConfig.methodSecurityExpressionHandler calls "
                        + "handler.setRoleHierarchy(roleHierarchy).");
    }

    @Test
    void adminOnlyUserDoesNotPassSuperAdminGatedEndpoint() {
        // Negative complement to the transitivity test above. ROLE_ADMIN sits below
        // ROLE_SUPER_ADMIN in the hierarchy, so it must NOT inherit super-admin's authority.
        HttpHeaders adminOnlyAuth = authenticateAs("hierarchy-admin-only",
                "hierarchy-admin-only@test.local", "pass-hao-1234",
                List.of("ROLE_ADMIN"));

        ResponseEntity<String> response = rest.exchange(
                url(SUPER_ADMIN_GATED_ENDPOINT),
                HttpMethod.GET,
                new HttpEntity<>(adminOnlyAuth),
                String.class);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(),
                "user with only ROLE_ADMIN must be rejected with 403 on a SUPER_ADMIN-gated "
                        + "endpoint — hierarchy is one-directional (upward only); got "
                        + response.getStatusCode());
    }

    @Test
    void userRoleDoesNotPassAdminGatedEndpoint() {
        // Smoke check: basic role isolation. A non-privileged user must not transitively
        // pass admin gates even though USER is below ADMIN in the hierarchy.
        HttpHeaders userOnlyAuth = authenticateAs("hierarchy-user-only",
                "hierarchy-user-only@test.local", "pass-huo-1234",
                List.of("ROLE_USER"));

        ResponseEntity<String> response = rest.exchange(
                url(ADMIN_GATED_ENDPOINT),
                HttpMethod.GET,
                new HttpEntity<>(userOnlyAuth),
                String.class);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(),
                "user with only ROLE_USER must be rejected with 403 on an ADMIN-gated "
                        + "endpoint — hierarchy is one-directional (USER does NOT inherit "
                        + "ADMIN); got " + response.getStatusCode());
    }
}
