package ai.operativus.agentmanager.integration.audit;

import ai.operativus.agentmanager.control.repository.SystemAuditRepository;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins {@code AuthController.authenticateUser}'s LOGIN_FAILURE
 *   audit-row writes:
 *   <ol>
 *     <li><b>Wrong password for existing user</b> → 401 + one LOGIN_FAILURE row recording
 *         the attempted username.</li>
 *     <li><b>Non-existent username</b> → 401 + LOGIN_FAILURE row recording the attempted
 *         username (privacy-relevant — attacker can enumerate usernames via audit log).</li>
 *     <li><b>Flood of N rapid failed logins</b> → all N rows persist. Confirmed safe to
 *         test because {@code RateLimitFilter} bypasses unauthenticated traffic per
 *         {@code RateLimitRuntimeTest.unauthenticatedTrafficBypassesRateLimiter}.</li>
 *   </ol>
 *
 *   <p>Privacy note pinned by case 2: today the system records the ATTEMPTED username on
 *   LOGIN_FAILURE, even when the username doesn't exist. An admin with audit-log read
 *   permission can enumerate which usernames exist by comparing LOGIN_FAILURE counts. If
 *   this leak is closed (e.g., by sanitizing the recorded username when the lookup fails),
 *   the case-2 test flips and the gap closes.
 *
 *   <p>Separate concern surfaced (NOT pinned here): unauthenticated rate-limit bypass means
 *   an attacker could DoS the audit table by flooding LOGIN_FAILUREs. Out of scope for this
 *   pin; flagged in PR body for product review.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class LoginFailureAuditRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {
            };

    @Autowired
    private SystemAuditRepository systemAuditRepository;

    @BeforeEach
    void resetState() {
        truncateDatabase();
    }

    @Test
    void failedLoginForExistingUserWithWrongPasswordWritesLoginFailureRow() {
        // Register a real user via authenticateAs (which uses the correct register body shape).
        String username = "login-fail-existing";
        authenticateAs(username, "login-fail-existing@test.local",
                "correct-password-12345", java.util.List.of("ROLE_USER"));

        long baseline = countLoginFailureRows();

        // Now attempt with wrong password.
        Map<String, Object> body = Map.of("username", username, "password", "wrong-password");
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/auth/login"),
                HttpMethod.POST,
                new HttpEntity<>(body),
                JSON_MAP);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode(),
                "wrong-password must return 401; got " + response.getStatusCode());

        long after = countLoginFailureRows();
        assertEquals(baseline + 1, after,
                "wrong password must produce exactly one LOGIN_FAILURE row; "
                        + "baseline=" + baseline + " after=" + after);
    }

    @Test
    void failedLoginForNonExistentUsernameWritesFailureRowWithoutLeakingUsername() {
        String fakeUsername = "definitely-does-not-exist-" + System.nanoTime();
        long baseline = countLoginFailureRows();

        Map<String, Object> body = Map.of("username", fakeUsername, "password", "anything");
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/auth/login"),
                HttpMethod.POST,
                new HttpEntity<>(body),
                JSON_MAP);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode(),
                "non-existent-user must return 401; got " + response.getStatusCode());

        // A failure row IS written even when the username doesn't exist...
        long after = countLoginFailureRows();
        assertEquals(baseline + 1, after,
                "a LOGIN_FAILURE row must be written even for a non-existent username");

        // ...but the attempted username must NOT be stored (anti-enumeration: production
        // records the LOGIN_FAILURE_PLACEHOLDER, so an audit-log reader cannot tell which
        // usernames were tried). This pins the deliberate placeholder design.
        long leaked = jdbc.queryForObject(
                "SELECT COUNT(*) FROM system_audits WHERE username = ? AND action = 'LOGIN_FAILURE'",
                Long.class, fakeUsername);
        assertEquals(0, leaked,
                "attempted username must NOT appear in the audit log (placeholder used instead)");
    }

    @Test
    void floodOfTwentyFailedLoginsAllPersist() {
        String username = "flood-target";
        authenticateAs(username, "flood-target@test.local",
                "correct-password-12345", java.util.List.of("ROLE_USER"));

        long baseline = countLoginFailureRows();

        for (int i = 0; i < 20; i++) {
            Map<String, Object> body = Map.of("username", username, "password", "wrong");
            ResponseEntity<Map<String, Object>> response = rest.exchange(
                    url("/api/auth/login"),
                    HttpMethod.POST,
                    new HttpEntity<>(body),
                    JSON_MAP);
            assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode(),
                    "attempt #" + i + " must return 401");
        }

        long after = countLoginFailureRows();
        assertEquals(baseline + 20, after,
                "20 rapid failed logins must all persist (no silent drop from rate-limiter; "
                        + "RateLimitFilter bypasses unauthenticated traffic per "
                        + "RateLimitRuntimeTest.unauthenticatedTrafficBypassesRateLimiter); "
                        + "baseline=" + baseline + " after=" + after);

        // Sanity: there should be exactly ONE LOGIN_SUCCESS row (from the fixture
        // authenticateAs call) and zero additional ones from the flood's bad passwords.
        long successRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM system_audits WHERE username = ? AND action = 'LOGIN_SUCCESS'",
                Long.class, username);
        assertEquals(1, successRows,
                "exactly one LOGIN_SUCCESS row from the authenticateAs fixture call; "
                        + "wrong-password flood must NOT produce additional successes; got "
                        + successRows);
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    // AuthController records LOGIN_FAILURE under a fixed placeholder username
    // (LOGIN_FAILURE_PLACEHOLDER, "<authentication-failed>") — deliberately NOT the
    // attempted username, to deny username enumeration via the audit log. So failure
    // rows are counted by action, not by attempted username. truncateDatabase() in
    // @BeforeEach isolates each test, so an action-only count is exact.
    private long countLoginFailureRows() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM system_audits WHERE action = 'LOGIN_FAILURE'",
                Long.class);
    }
}
