package ai.operativus.agentmanager.integration.a2a;

import ai.operativus.agentmanager.control.security.ApiKeyAuthenticationFilter;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import ai.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Locks the inbound auth contract on {@code /api/v1/a2a/**} — the
 *   {@link ApiKeyAuthenticationFilter} + JWT dual-auth interaction. The {@code A2aMesh}
 *   sibling test only exercises the JWT-Bearer path; this test pins the
 *   {@code X-A2A-Api-Key} machine-to-machine path that the FE peer-registration page
 *   advertises as the credential remote peers should send.
 *
 *   Cases:
 *     1. Missing {@code X-A2A-Api-Key} header + no JWT → 401 with the documented error JSON.
 *     2. Blank header value → 401 (treated as missing).
 *     3. Wrong API key + no JWT → 401 (constant-time loop completes before reject).
 *     4. Valid API key + no JWT → 200; the request reaches the controller.
 *     5. JWT-authenticated caller without {@code X-A2A-Api-Key} → 200; dual-auth bypass
 *        per the filter contract.
 *
 *   Test-only property {@code agm.a2a.api-keys} seeds two known keys so cases 3 and 4
 *   can run without touching production config.
 * State: Stateless. Inherits {@link BaseIntegrationTest} scaffolding.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
@TestPropertySource(properties = "agm.a2a.api-keys=test-key-good-1,test-key-good-2")
public class A2aInboundAuthRuntimeTest extends BaseIntegrationTest {

    private static final String CARDS_PATH = "/api/v1/a2a/cards";
    private static final ParameterizedTypeReference<List<Map<String, Object>>> JSON_LIST =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    // ─── Case 1 — Missing header + no JWT → 401 ───

    @Test
    void inbound_noApiKeyAndNoJwt_returns401_withMissingHeaderMessage() {
        HttpHeaders noAuth = new HttpHeaders();

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url(CARDS_PATH), HttpMethod.GET, new HttpEntity<>(noAuth), JSON_MAP);

        assertEquals(401, response.getStatusCode().value(),
                "GET /a2a/cards without any auth must 401 — both filters reject");
        assertTrue(response.getBody() != null && response.getBody().toString().contains("Missing"),
                "401 body must carry the documented 'Missing X-A2A-Api-Key header.' message; got "
                        + response.getBody());
    }

    // ─── Case 2 — Blank header → 401 (treated as missing) ───

    @Test
    void inbound_blankApiKey_returns401() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(ApiKeyAuthenticationFilter.A2A_API_KEY_HEADER, "");

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url(CARDS_PATH), HttpMethod.GET, new HttpEntity<>(headers), JSON_MAP);

        assertEquals(401, response.getStatusCode().value(),
                "Blank X-A2A-Api-Key must be treated as missing (StringUtils.hasText guard)");
    }

    // ─── Case 3 — Wrong key + no JWT → 401 (constant-time loop, then reject) ───

    @Test
    void inbound_wrongApiKey_returns401_withInvalidKeyMessage() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(ApiKeyAuthenticationFilter.A2A_API_KEY_HEADER, "this-key-is-not-on-the-list");

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url(CARDS_PATH), HttpMethod.GET, new HttpEntity<>(headers), JSON_MAP);

        assertEquals(401, response.getStatusCode().value(),
                "wrong API key must 401");
        assertTrue(response.getBody() != null && response.getBody().toString().contains("Invalid"),
                "401 body must carry the 'Invalid or revoked API key.' message; got " + response.getBody());
    }

    // ─── Case 4 — Valid key + no JWT → 200 ───

    @Test
    void inbound_validApiKey_returns200_passesFilterChain() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(ApiKeyAuthenticationFilter.A2A_API_KEY_HEADER, "test-key-good-1");

        ResponseEntity<List<Map<String, Object>>> response = rest.exchange(
                url(CARDS_PATH), HttpMethod.GET, new HttpEntity<>(headers), JSON_LIST);

        assertEquals(200, response.getStatusCode().value(),
                "valid X-A2A-Api-Key from agm.a2a.api-keys must pass — request reaches controller");
    }

    // ─── Case 5 — JWT auth, no api-key → 200 (dual-auth bypass) ───

    @Test
    void inbound_jwtBearerBypassesApiKeyFilter() {
        // authenticateAs sets a JWT-bearing Authorization header; the api-key filter sees
        // a populated SecurityContext and short-circuits before checking the header.
        HttpHeaders auth = authenticateAs("a2a-dual-auth", "a2a-dual-auth@test.local",
                "pass-a2a-1234", List.of("ROLE_USER"));

        ResponseEntity<List<Map<String, Object>>> response = rest.exchange(
                url(CARDS_PATH), HttpMethod.GET, new HttpEntity<>(auth), JSON_LIST);

        assertEquals(200, response.getStatusCode().value(),
                "JWT-authenticated caller must bypass the api-key filter (dual-auth contract)");
    }
}
