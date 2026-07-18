package ai.operativus.agentmanager.integration;

import ai.operativus.agentmanager.core.model.AuthModels.JwtResponse;
import ai.operativus.agentmanager.core.model.AuthModels.LoginRequest;
import ai.operativus.agentmanager.core.model.AuthModels.RegisterRequest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Domain Responsibility: Black-box HTTP coverage of the authentication flow — register,
 *   login (token issuance), unauthenticated 401, and authenticated 200.
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class AuthenticationIntegrationTest extends BaseIntegrationTest {

    @BeforeEach
    void resetState() {
        truncateDatabase();
    }

    @Test
    void fullAuthenticationFlow() {
        // 1. Register
        RegisterRequest registerRequest = new RegisterRequest(
                "auth-test-user", "auth@test.local", "password123", List.of("ROLE_USER"));
        ResponseEntity<String> registerResp = rest.postForEntity(
                url("/api/auth/register"), registerRequest, String.class);
        assertEquals(HttpStatus.OK, registerResp.getStatusCode(),
                "Register must return 200; got " + registerResp.getStatusCode());

        // 2. Login → obtain JWT
        LoginRequest loginRequest = new LoginRequest("auth-test-user", "password123");
        ResponseEntity<JwtResponse> loginResp = rest.postForEntity(
                url("/api/auth/login"), loginRequest, JwtResponse.class);
        assertEquals(HttpStatus.OK, loginResp.getStatusCode(),
                "Login must return 200; got " + loginResp.getStatusCode());
        assertNotNull(loginResp.getBody(), "Login response body must not be null");
        String token = loginResp.getBody().token();
        assertNotNull(token, "JWT token must be present in login response");

        // 3. Protected endpoint without token → 401
        ResponseEntity<String> unauthed = rest.exchange(
                url("/api/agents"), HttpMethod.GET, HttpEntity.EMPTY, String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, unauthed.getStatusCode(),
                "GET /api/agents without token must return 401; got " + unauthed.getStatusCode());

        // 4. Protected endpoint with token → 200
        HttpHeaders auth = new HttpHeaders();
        auth.setContentType(MediaType.APPLICATION_JSON);
        auth.setBearerAuth(token);
        ResponseEntity<String> authed = rest.exchange(
                url("/api/agents"), HttpMethod.GET, new HttpEntity<>(auth), String.class);
        assertEquals(HttpStatus.OK, authed.getStatusCode(),
                "GET /api/agents with valid token must return 200; got " + authed.getStatusCode());
    }
}
