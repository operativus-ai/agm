package com.operativus.agentmanager.integration.auth;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins that POST {@code /api/auth/login} produces
 *   <b>indistinguishable</b> responses for the two failure modes that an attacker would
 *   use to enumerate usernames:
 *   <ul>
 *     <li>Unknown username + any password → 401</li>
 *     <li>Known username + wrong password → 401</li>
 *   </ul>
 *   Both must return the same HTTP status, content-type, and response body. The body
 *   must not contain either attempted username.
 *
 *   <p>The behavior pinned here depends on two layered defaults that this test guards
 *   against silent regression:
 *   <ol>
 *     <li><b>Spring Security default</b> — {@code DaoAuthenticationProvider} ships with
 *         {@code hideUserNotFoundExceptions=true} and lazy-initializes a dummy bcrypt
 *         hash via {@code prepareTimingAttackProtection}. When
 *         {@code UserDetailsServiceImpl.loadUserByUsername} throws
 *         {@code UsernameNotFoundException} for an unknown user, the provider
 *         (a) calls {@code passwordEncoder.matches(presented, USER_NOT_FOUND_PASSWORD_HASH)}
 *         to equalize timing with the valid-user wrong-password path, then
 *         (b) re-throws as {@code BadCredentialsException} — the same exception thrown
 *         for the wrong-password path. If anyone calls
 *         {@code authProvider.setHideUserNotFoundExceptions(false)} or swaps to a
 *         custom provider without this mitigation, the response body diverges and this
 *         test fails.</li>
 *     <li><b>GlobalExceptionHandler.handleBadCredentialsException</b> — flattens every
 *         {@code BadCredentialsException} (regardless of root cause) to a generic
 *         {@code ProblemDetail} JSON containing only "Invalid username or password.".
 *         If anyone narrows that handler to surface the underlying reason, this test
 *         fails.</li>
 *   </ol>
 *
 *   <p>Separate concern not pinned here: rate limiting on {@code /api/auth/login}.
 *   Timing-equalized responses still allow online dictionary attacks at ~1 req/bcrypt-cost.
 *   Tracked as deferred follow-up from PR #1023.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class LoginEnumerationRegressionTest extends BaseIntegrationTest {

    @BeforeEach
    void resetState() {
        truncateDatabase();
    }

    @Test
    void unknownUserAndWrongPasswordProduceIndistinguishableResponses() {
        String knownUser = "enum-known-" + UUID.randomUUID().toString().substring(0, 8);
        String knownPassword = "correct-password-12345";
        String unknownUser = "enum-unknown-" + UUID.randomUUID().toString().substring(0, 8);

        rest.postForEntity(url("/api/auth/register"),
                Map.of("username", knownUser,
                        "email", knownUser + "@test.local",
                        "password", knownPassword),
                Void.class);

        ResponseEntity<String> wrongPasswordResponse = postLogin(knownUser, "wrong-password-67890");
        ResponseEntity<String> unknownUserResponse = postLogin(unknownUser, "any-password-at-all");

        assertEquals(HttpStatus.UNAUTHORIZED, wrongPasswordResponse.getStatusCode(),
                "wrong-password must return 401");
        assertEquals(HttpStatus.UNAUTHORIZED, unknownUserResponse.getStatusCode(),
                "unknown-user must return 401 (NOT 404 — that would leak existence)");

        assertEquals(wrongPasswordResponse.getStatusCode(), unknownUserResponse.getStatusCode(),
                "status codes must match — divergence enables enumeration");

        assertEquals(
                wrongPasswordResponse.getHeaders().getContentType(),
                unknownUserResponse.getHeaders().getContentType(),
                "content-type must match — divergence enables enumeration");

        assertEquals(wrongPasswordResponse.getBody(), unknownUserResponse.getBody(),
                "response bodies must be byte-identical. divergence enables enumeration via "
                        + "comparing JSON shape/content. wrong-password=" + wrongPasswordResponse.getBody()
                        + " unknown-user=" + unknownUserResponse.getBody());
    }

    @Test
    void failureResponseLeaksNeitherAttemptedUsernameNorEmail() {
        String knownUser = "enum-leak-known-" + UUID.randomUUID().toString().substring(0, 8);
        String knownEmail = knownUser + "@test.local";
        String unknownUser = "enum-leak-unknown-" + UUID.randomUUID().toString().substring(0, 8);

        rest.postForEntity(url("/api/auth/register"),
                Map.of("username", knownUser,
                        "email", knownEmail,
                        "password", "correct-password-12345"),
                Void.class);

        ResponseEntity<String> wrongPasswordResponse = postLogin(knownUser, "wrong");
        ResponseEntity<String> unknownUserResponse = postLogin(unknownUser, "wrong");

        for (ResponseEntity<String> response : new ResponseEntity[]{wrongPasswordResponse, unknownUserResponse}) {
            String body = response.getBody();
            assertFalse(body.contains(knownUser),
                    "response body must not echo the known username: " + body);
            assertFalse(body.contains(knownEmail),
                    "response body must not echo the known email: " + body);
            assertFalse(body.contains(unknownUser),
                    "response body must not echo the attempted unknown username: " + body);
        }
    }

    @Test
    void failureResponseMatchesProblemDetailContract() {
        ResponseEntity<String> response = postLogin(
                "contract-pin-" + UUID.randomUUID().toString().substring(0, 8),
                "any-password");

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals(MediaType.APPLICATION_PROBLEM_JSON,
                response.getHeaders().getContentType(),
                "GlobalExceptionHandler.handleBadCredentialsException must emit application/problem+json");

        String body = response.getBody();
        assertTrue(body.contains("\"status\":401"),
                "ProblemDetail must include status=401; body=" + body);
        assertTrue(body.contains("\"title\":\"Unauthorized Access\""),
                "ProblemDetail must pin the generic title; body=" + body);
        assertTrue(body.contains("\"detail\":\"Invalid username or password.\""),
                "ProblemDetail must pin the generic detail; body=" + body);
        assertTrue(body.contains("\"type\":\"urn:problem-type:unauthorized\""),
                "ProblemDetail must pin the generic type URI; body=" + body);
    }

    private ResponseEntity<String> postLogin(String username, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(
                url("/api/auth/login"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("username", username, "password", password), headers),
                String.class);
    }
}
