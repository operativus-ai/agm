package ai.operativus.agentmanager.integration.auth;

import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins that successful login stamps
 *   {@code users.last_login_at}. The setter existed on {@code User.lastLoginAt} and the
 *   field was surfaced through {@code UserAdminDTO}, but no path actually wrote it before
 *   this PR — so admin/UI consumers always saw {@code null}. Pinning the new wire-up
 *   prevents silent regression to the dead-code state.
 *
 *   <p>Two cases:
 *   <ul>
 *     <li>Initial login (lastLoginAt was null) → stamp non-null after login</li>
 *     <li>Failed login attempt → lastLoginAt MUST NOT advance (only successful auth stamps)</li>
 *   </ul>
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class LoginStampsLastLoginAtRuntimeTest extends BaseIntegrationTest {

    @Test
    void successfulLogin_stampsLastLoginAt() {
        String username = "login-stamp-" + UUID.randomUUID().toString().substring(0, 8);
        String password = "pwd-stamp-1234";

        rest.postForEntity(url("/api/auth/register"),
                Map.of("username", username, "email", username + "@test.local", "password", password),
                Void.class);

        // Pre-state: lastLoginAt is null because registration does not stamp it.
        Timestamp pre = lastLoginAtOf(username);
        assertEquals(null, pre,
                "fixture: brand-new account must have last_login_at=null; got " + pre);

        LocalDateTime before = LocalDateTime.now();

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/auth/login"),
                org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(Map.of("username", username, "password", password), HttpHeaders.EMPTY),
                new org.springframework.core.ParameterizedTypeReference<>() {});

        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "fixture: login must succeed; got " + resp.getStatusCode());

        Timestamp post = lastLoginAtOf(username);
        assertAll("successful login stamps last_login_at",
                () -> assertNotNull(post,
                        "last_login_at MUST be non-null after login — the wire-up in "
                                + "AuthController.authenticateUser is the only path that writes "
                                + "this field; a null here means the wire-up regressed and "
                                + "admin consumers will see stale dead-code behavior"),
                () -> assertTrue(!post.toLocalDateTime().isBefore(before),
                        "stamped value must not be before the login attempt; got " + post));
    }

    @Test
    void failedLogin_doesNotAdvanceLastLoginAt() {
        String username = "login-fail-" + UUID.randomUUID().toString().substring(0, 8);
        String password = "pwd-fail-1234";

        rest.postForEntity(url("/api/auth/register"),
                Map.of("username", username, "email", username + "@test.local", "password", password),
                Void.class);

        // Bad-password attempt must NOT stamp last_login_at — the LOGIN_FAILURE branch
        // throws before reaching the userRepository.save in the success branch.
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/auth/login"),
                org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(Map.of("username", username, "password", "wrong-pwd"), HttpHeaders.EMPTY),
                new org.springframework.core.ParameterizedTypeReference<>() {});

        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode(),
                "fixture: bad-password login must return 401; got " + resp.getStatusCode());

        Timestamp post = lastLoginAtOf(username);
        assertEquals(null, post,
                "failed login MUST NOT advance last_login_at — pinning the success-only "
                        + "contract prevents a future refactor from stamping on every attempt "
                        + "(which would mislead lockout / inactivity sweepers); got " + post);
    }

    private Timestamp lastLoginAtOf(String username) {
        return jdbc.queryForObject(
                "SELECT last_login_at FROM users WHERE username = ?",
                Timestamp.class, username);
    }
}
