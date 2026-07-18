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

import java.time.Duration;
import java.time.Instant;
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
 * Domain Responsibility: Pins the {@code WEBHOOK_TIMEOUT = 5s} contract inside
 *   {@link ai.operativus.agentmanager.compute.advisor.ExtensionHookAdvisor#dispatchHook}
 *   — when a webhook stalls (slow-loris), the Mono's {@code .timeout(WEBHOOK_TIMEOUT)}
 *   operator fires before the 6s server-side delay, the advisor's try/catch swallows
 *   the timeout exception, and the agent run completes normally. Existing fail-safe
 *   tests cover only immediate HTTP 500 responses; this is the first runtime test
 *   that exercises the slow-response path.
 *
 * State: Stateless. Inherits Testcontainers Postgres + full app context from
 *   {@link BaseIntegrationTest}. Per-class WireMock with {@code withFixedDelay(6000)}
 *   on the stub.
 *
 * <p>Timing assertion uses a generous upper bound (~10s) rather than exact 5s + epsilon
 * to absorb CI clock noise. The point is the run does NOT wait the full 6s server
 * delay — the timeout operator MUST fire, and the wall-clock difference between 5s
 * and 6s is small enough that a generous upper bound is the only stable check.</p>
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class ExtensionWebhookTimeoutRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    /** Server-side delay > advisor's WEBHOOK_TIMEOUT (5s). */
    private static final int SLOW_LORIS_DELAY_MS = 6_000;

    /**
     * Upper wall-clock bound for the whole run. WEBHOOK_TIMEOUT is 5s; full-context boot
     * is already paid by the time this test runs. Anything > 10s would mean the advisor
     * is NOT applying its timeout — that's the regression we're catching.
     */
    private static final Duration MAX_ALLOWED_RUN_DURATION = Duration.ofSeconds(10);

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
    void webhookHangsPast5sTimeout_advisorBailsAndRunCompletes() {
        HttpHeaders auth = authenticatedHeaders("ext-timeout-runner");

        String hookId = "ext-timeout-" + UUID.randomUUID();
        String hookPath = "/hooks/slow-loris-" + hookId;
        wiremock.stubFor(post(urlPathEqualTo(hookPath))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay(SLOW_LORIS_DELAY_MS)
                        .withBody("would-be-fine-if-anyone-waited")));

        registerWebhook(auth, hookId, "http://localhost:" + wiremock.port() + hookPath);

        String agentId = createAgentWithPostHook(auth, "E5 timeout agent", hookId);
        fakeModel.respondWith("E5 reply");

        Map<String, Object> runBody = new HashMap<>();
        runBody.put("message", "E5 prompt");
        runBody.put("sessionId", "session-" + UUID.randomUUID());

        Instant before = Instant.now();
        ResponseEntity<Map<String, Object>> run = rest.exchange(
                url("/api/agents/" + agentId + "/runs"),
                HttpMethod.POST, new HttpEntity<>(runBody, auth), JSON_MAP);
        Duration elapsed = Duration.between(before, Instant.now());

        assertThat(run.getStatusCode())
                .as("a webhook hanging past WEBHOOK_TIMEOUT MUST NOT fail the run — the timeout exception is swallowed inside dispatchHook")
                .isEqualTo(HttpStatus.OK);
        assertThat(run.getBody().get("status")).isEqualTo("COMPLETED");

        assertThat(elapsed)
                .as("run wall-clock MUST be bounded by WEBHOOK_TIMEOUT (5s) plus normal overhead, NOT the server delay (6s) plus overhead. Observed: %s", elapsed)
                .isLessThan(MAX_ALLOWED_RUN_DURATION);

        long attempted = wiremock.getAllServeEvents().stream()
                .filter(e -> hookPath.equals(e.getRequest().getUrl())).count();
        assertThat(attempted)
                .as("the advisor MUST have attempted the dispatch — the swallowed timeout is the point, not a skip")
                .isEqualTo(1L);
    }

    // ─── fixtures ───

    private void registerWebhook(HttpHeaders auth, String id, String url) {
        Map<String, Object> regBody = new HashMap<>();
        regBody.put("id", id);
        regBody.put("name", "E5 slow-loris webhook");
        regBody.put("type", "WEBHOOK");
        regBody.put("url", url);
        regBody.put("description", "E5 fixture");
        regBody.put("active", true);
        ResponseEntity<Map<String, Object>> r = rest.exchange(
                url("/api/v1/extensions"), HttpMethod.POST, new HttpEntity<>(regBody, auth), JSON_MAP);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private String createAgentWithPostHook(HttpHeaders auth, String name, String hookId) {
        String agentId = "agent-" + UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId);
        body.put("name", name);
        body.put("description", "E5 fixture");
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
        body.put("preHooks", List.of());
        body.put("postHooks", List.of(hookId));
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
        return authenticateAs(username, username + "@test.local", "pass-e5-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }
}
