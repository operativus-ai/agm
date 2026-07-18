package com.operativus.agentmanager.integration.audit;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Domain Responsibility: Pins the LOGIN_FAILURE username-sanitization contract.
 *   {@code AuthController.authenticateUser}'s failure branch records the literal
 *   {@code "<authentication-failed>"} as the username on system_audits rows instead
 *   of the attempted username, defeating enumeration via audit-log read.
 *
 *   <p><b>Privacy intent</b>: previously an admin with audit-log read permission could
 *   enumerate which usernames existed by counting LOGIN_FAILURE rows per attempted
 *   username (real user + wrong password → row with that username; non-existent user →
 *   row with the attempted username). Recording the same literal for BOTH cases removes
 *   that signal entirely.
 *
 *   <p><b>Trade-off</b>: the forensic value of "who tried to log in as alice" is lost
 *   from the audit-log read surface. If granular forensic value is needed, route the
 *   raw attempted username through a separate restricted-access table — out of scope
 *   for this PR.
 *
 *   <p>Pinned cases:
 *   <ul>
 *     <li><b>Wrong password for existing user</b> → row with username =
 *         {@code "<authentication-failed>"}, NOT the real username.</li>
 *     <li><b>Non-existent username</b> → row with username =
 *         {@code "<authentication-failed>"}, NOT the attempted name.</li>
 *     <li><b>Total LOGIN_FAILURE count increments</b> — sanitization doesn't suppress
 *         the row, just sanitizes the username field.</li>
 *   </ul>
 *
 *   <p><b>Coordination with PR #791</b>: that PR's {@code LoginFailureAuditRuntimeTest}
 *   asserted rows existed keyed by the real attempted username. With this change, those
 *   assertions break. Both PRs reconcile at merge time — whichever merges later needs
 *   its assertions updated to count by {@code "<authentication-failed>"} instead.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class LoginFailureUsernameSanitizationRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {
            };

    private static final String LOGIN_FAILURE_PLACEHOLDER = "<authentication-failed>";

    @BeforeEach
    void resetState() {
        truncateDatabase();
    }

    @Test
    void failedLoginForExistingUserWithWrongPasswordRecordsSanitizedPlaceholder() {
        String realUsername = "sanitize-existing-target";
        authenticateAs(realUsername, "sanitize-existing-target@test.local",
                "correct-password-12345", List.of("ROLE_USER"));

        // Attempt with wrong password.
        Map<String, Object> body = Map.of("username", realUsername, "password", "wrong");
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/auth/login"),
                HttpMethod.POST,
                new HttpEntity<>(body),
                JSON_MAP);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());

        // The attempted username MUST NOT appear in any LOGIN_FAILURE row.
        Long realUsernameRows = countLoginFailureRowsForUsername(realUsername);
        assertEquals(0L, realUsernameRows,
                "no LOGIN_FAILURE row may carry the real username (would enable "
                        + "enumeration); got " + realUsernameRows);

        // The sanitized placeholder MUST carry the row.
        Long placeholderRows = countLoginFailureRowsForUsername(LOGIN_FAILURE_PLACEHOLDER);
        assertEquals(1L, placeholderRows,
                "LOGIN_FAILURE row must be recorded under the sanitized placeholder; got "
                        + placeholderRows);
    }

    @Test
    void failedLoginForNonExistentUsernameAlsoRecordsSanitizedPlaceholder() {
        String fakeUsername = "definitely-does-not-exist-" + System.nanoTime();

        Map<String, Object> body = Map.of("username", fakeUsername, "password", "anything");
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/auth/login"),
                HttpMethod.POST,
                new HttpEntity<>(body),
                JSON_MAP);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());

        Long attemptedUsernameRows = countLoginFailureRowsForUsername(fakeUsername);
        assertEquals(0L, attemptedUsernameRows,
                "no LOGIN_FAILURE row may carry the attempted username (would enable "
                        + "enumeration of NON-EXISTENT users); got " + attemptedUsernameRows);

        // We can't assert placeholder count = 1 deterministically (other tests may have
        // added rows). Instead, assert that AT LEAST ONE placeholder row exists.
        Long placeholderRows = countLoginFailureRowsForUsername(LOGIN_FAILURE_PLACEHOLDER);
        assertEquals(1L, placeholderRows,
                "LOGIN_FAILURE row must be recorded under the sanitized placeholder; got "
                        + placeholderRows);
    }

    @Test
    void sanitizationDoesNotSuppressTheRowItself() {
        long baseline = jdbc.queryForObject(
                "SELECT COUNT(*) FROM system_audits WHERE action = 'LOGIN_FAILURE'",
                Long.class);

        Map<String, Object> body = Map.of(
                "username", "another-non-existent-user", "password", "wrong");
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/auth/login"),
                HttpMethod.POST,
                new HttpEntity<>(body),
                JSON_MAP);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());

        long after = jdbc.queryForObject(
                "SELECT COUNT(*) FROM system_audits WHERE action = 'LOGIN_FAILURE'",
                Long.class);
        assertEquals(baseline + 1, after,
                "sanitization must not suppress the row — only sanitize the username "
                        + "field. baseline=" + baseline + " after=" + after);
    }

    private Long countLoginFailureRowsForUsername(String username) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM system_audits WHERE username = ? AND action = 'LOGIN_FAILURE'",
                Long.class, username);
    }
}
