package ai.operativus.agentmanager.integration.audit;

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
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Domain Responsibility: Pins {@code AuthController.logout}'s LOGOUT audit-row write.
 *   The endpoint is a thin wrapper around {@code SystemAuditService.record(..., "LOGOUT",
 *   ...)} and {@link org.springframework.security.core.context.SecurityContextHolder#clearContext()}.
 *
 *   <p>Pinned cases:
 *   <ul>
 *     <li><b>Authenticated POST /api/auth/logout</b> → 204 + one LOGOUT audit row keyed
 *         by the caller's username.</li>
 *     <li><b>Unauthenticated POST /api/auth/logout</b> → 401 (Spring auth filter rejects
 *         before the handler runs; NO LOGOUT row written).</li>
 *   </ul>
 *
 *   <p>Completes the auth-event audit set:
 *   <ul>
 *     <li>LOGIN_SUCCESS, LOGIN_FAILURE — {@code AuditLogsRuntimeTest},
 *         {@code LoginFailureAuditRuntimeTest}</li>
 *     <li>REGISTER — {@code RegisterAuditEventRuntimeTest} (PR #792)</li>
 *     <li>LOGOUT — this PR</li>
 *   </ul>
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class LogoutAuditEventRuntimeTest extends BaseIntegrationTest {

    @BeforeEach
    void resetState() {
        truncateDatabase();
    }

    @Test
    void authenticatedLogoutWritesOneLogoutAuditRow() {
        String username = "logout-audit-target";
        HttpHeaders auth = authenticateAs(username, "logout-audit-target@test.local",
                "pass-lat-1234", List.of("ROLE_USER"));

        long baseline = countLogoutRows(username);

        ResponseEntity<Void> response = rest.exchange(
                url("/api/auth/logout"),
                HttpMethod.POST,
                new HttpEntity<>(auth),
                Void.class);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode(),
                "POST /api/auth/logout with valid auth must return 204; got "
                        + response.getStatusCode());

        long after = countLogoutRows(username);
        assertEquals(baseline + 1, after,
                "logout must write exactly 1 LOGOUT audit row for the caller; baseline="
                        + baseline + " after=" + after);
    }

    @Test
    void unauthenticatedLogoutIsRejectedAndWritesNoLogoutRow() {
        long baseline = jdbc.queryForObject(
                "SELECT COUNT(*) FROM system_audits WHERE action = 'LOGOUT'", Long.class);

        ResponseEntity<Void> response = rest.exchange(
                url("/api/auth/logout"),
                HttpMethod.POST,
                new HttpEntity<>((Object) null, new HttpHeaders()),
                Void.class);

        // /api/auth/** is in the permitAll allowlist, so the request reaches the handler.
        // The @PreAuthorize("isAuthenticated()") then rejects the anonymous principal — Spring
        // returns 403 (not 401) because the request was technically allowed past the security
        // filter but failed method-level auth.
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(),
                "unauthenticated logout must be rejected by @PreAuthorize with 403; got "
                        + response.getStatusCode());

        long after = jdbc.queryForObject(
                "SELECT COUNT(*) FROM system_audits WHERE action = 'LOGOUT'", Long.class);
        assertEquals(baseline, after,
                "unauthenticated logout must NOT write a LOGOUT audit row; baseline="
                        + baseline + " after=" + after);
    }

    private long countLogoutRows(String username) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM system_audits WHERE username = ? AND action = 'LOGOUT'",
                Long.class, username);
    }
}
