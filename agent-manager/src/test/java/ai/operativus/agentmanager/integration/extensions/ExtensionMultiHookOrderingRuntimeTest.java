package ai.operativus.agentmanager.integration.extensions;

import com.github.tomakehurst.wiremock.WireMockServer;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModel;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import ai.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Domain Responsibility: Pins per-hook fail isolation in
 *   {@link ai.operativus.agentmanager.compute.advisor.ExtensionHookAdvisor}. The
 *   advisor iterates {@code preHookIds} and {@code postHookIds} sequentially with a
 *   try/catch around each {@code dispatchHook} call. If hook B throws, hooks A and C
 *   must still fire and the agent run must complete normally. Existing tests cover
 *   single hooks only — this case proves the per-hook catch boundary works in
 *   sequence.
 *
 * State: Stateless. Inherits Testcontainers Postgres + full app context from
 *   {@link BaseIntegrationTest}. Uses per-class WireMock for the three webhook
 *   targets and {@link FakeChatModel} so the LLM reply is scriptable.
 *
 * <p>Setup: three WEBHOOK extensions, hookA → 200, hookB → 500 (representing the
 * middle-failure case), hookC → 200. Agent's {@code preHooks} list is {@code [A, B, C]}
 * in order. The advisor's catch boundary should produce three POST attempts (one each)
 * with the run completing as COMPLETED.</p>
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class ExtensionMultiHookOrderingRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    private static WireMockServer wiremock;

    @Autowired private FakeChatModel fakeModel;

    @BeforeAll
    static void startWireMock() {
        wiremock = new WireMockServer(options().dynamicPort());
        wiremock.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (wiremock != null) wiremock.stop();
    }

    @BeforeEach
    void resetFixtures() {
        wiremock.resetAll();
        fakeModel.reset();
        seedModel("gpt-4o-mini");
    }

    @Test
    void multiplePreHooks_middleOneThrowing_othersStillFire_runCompletes() {
        HttpHeaders auth = authenticatedHeaders("ext-multi-runner");

        String hookA = "ext-multi-A-" + UUID.randomUUID();
        String hookB = "ext-multi-B-" + UUID.randomUUID();
        String hookC = "ext-multi-C-" + UUID.randomUUID();
        String pathA = "/hooks/multi-A-" + hookA;
        String pathB = "/hooks/multi-B-" + hookB;
        String pathC = "/hooks/multi-C-" + hookC;

        wiremock.stubFor(post(urlPathEqualTo(pathA))
                .willReturn(aResponse().withStatus(200).withBody("ok")));
        wiremock.stubFor(post(urlPathEqualTo(pathB))
                .willReturn(aResponse().withStatus(500).withBody("boom from B")));
        wiremock.stubFor(post(urlPathEqualTo(pathC))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        registerWebhook(auth, hookA, "http://localhost:" + wiremock.port() + pathA);
        registerWebhook(auth, hookB, "http://localhost:" + wiremock.port() + pathB);
        registerWebhook(auth, hookC, "http://localhost:" + wiremock.port() + pathC);

        String agentId = createAgentWithPreHooks(auth, "E4 multi-hook agent",
                List.of(hookA, hookB, hookC));

        fakeModel.respondWith("E4 LLM reply after multi-hook prelude");

        Map<String, Object> runBody = new HashMap<>();
        runBody.put("message", "E4 prompt");
        runBody.put("sessionId", "session-" + UUID.randomUUID());
        ResponseEntity<Map<String, Object>> run = rest.exchange(
                url("/api/agents/" + agentId + "/runs"),
                HttpMethod.POST, new HttpEntity<>(runBody, auth), JSON_MAP);

        assertThat(run.getStatusCode())
                .as("middle pre-hook failing MUST NOT fail the run — per-hook fail-safe boundary")
                .isEqualTo(HttpStatus.OK);
        assertThat(run.getBody().get("status")).isEqualTo("COMPLETED");

        long countA = wiremock.getAllServeEvents().stream()
                .filter(e -> pathA.equals(e.getRequest().getUrl())).count();
        long countB = wiremock.getAllServeEvents().stream()
                .filter(e -> pathB.equals(e.getRequest().getUrl())).count();
        long countC = wiremock.getAllServeEvents().stream()
                .filter(e -> pathC.equals(e.getRequest().getUrl())).count();

        assertThat(countA)
                .as("hookA precedes the failing hookB; MUST have been dispatched")
                .isEqualTo(1L);
        assertThat(countB)
                .as("hookB is attempted even though it returns 500 — the WebClient call fires, the 500 is swallowed")
                .isEqualTo(1L);
        assertThat(countC)
                .as("hookC follows the failing hookB; the per-hook try/catch around dispatchHook MUST let the loop continue")
                .isEqualTo(1L);
    }

    // ─── fixtures ───

    private void registerWebhook(HttpHeaders auth, String id, String url) {
        Map<String, Object> regBody = new HashMap<>();
        regBody.put("id", id);
        regBody.put("name", "E4 webhook " + id);
        regBody.put("type", "WEBHOOK");
        regBody.put("url", url);
        regBody.put("description", "E4 fixture");
        regBody.put("active", true);
        ResponseEntity<Map<String, Object>> r = rest.exchange(
                url("/api/v1/extensions"), HttpMethod.POST, new HttpEntity<>(regBody, auth), JSON_MAP);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private String createAgentWithPreHooks(HttpHeaders auth, String name, List<String> preHooks) {
        String agentId = "agent-" + UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId);
        body.put("name", name);
        body.put("description", "E4 fixture");
        body.put("instructions", "Be helpful.");
        body.put("model", "gpt-4o-mini");
        body.put("isReasoningEnabled", false);
        body.put("isTeam", false);
        body.put("requiresPiiRedaction", false);
        body.put("approvedForProduction", false);
        body.put("maintenanceMode", false);
        body.put("active", true);
        body.put("enforceJsonOutput", false);
        body.put("memoryEnabled", true);
        body.put("addHistoryToMessages", true);
        body.put("preHooks", preHooks);
        body.put("postHooks", List.of());
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/admin/agents"), HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return agentId;
    }

    private void seedModel(String modelId) {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES (?, ?, 'fake', ?, true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """, modelId, modelId, modelId);
    }

    private HttpHeaders authenticatedHeaders(String username) {
        return authenticateAs(username, username + "@test.local", "pass-e4-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }
}
