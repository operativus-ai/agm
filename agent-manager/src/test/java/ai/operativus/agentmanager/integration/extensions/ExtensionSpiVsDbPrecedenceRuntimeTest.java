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
 * Domain Responsibility: Pins the aligned SPI-vs-DB precedence in the extensions surface
 *   after the F3 production fix. When an
 *   {@link ai.operativus.agentmanager.core.spi.AgentHookExtension} and a DB-backed
 *   WEBHOOK extension share the same {@code extensionId}, BOTH the list view AND the
 *   dispatch path resolve to the DB row. SPI is the fallback ONLY when no DB row exists
 *   for that id.
 *
 * State: Stateless. Inherits Testcontainers Postgres + full app context from
 *   {@link BaseIntegrationTest}. Per-class WireMock + {@link FakeChatModel}.
 *
 * <p>Test SPI implementation: {@link E6TestHookSpi}, registered via test-classpath
 * service-loader file. Its dispatch counters MUST remain at 0 when the DB row is
 * present — the F3 fix means SPI doesn't fire under collision.</p>
 *
 * <p>This test was a REGRESSION-LOCK pre-F3 (it pinned the dispatch-fires-SPI,
 * list-shows-DB divergence). The dispatch assertion has been flipped: webhook MUST
 * fire (1 POST observed), SPI MUST NOT fire (counter stays 0).</p>
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class ExtensionSpiVsDbPrecedenceRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> JSON_LIST =
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
        E6TestHookSpi.resetCounters();
    }

    @Test
    void spiAndDbSameId_bothListAndDispatchResolveToDb_F3ProductionFix() {
        HttpHeaders auth = authenticatedHeaders("ext-precedence-runner");

        String hookId = E6TestHookSpi.EXTENSION_ID; // collide with the SPI on purpose
        String hookPath = "/hooks/precedence-" + UUID.randomUUID();
        wiremock.stubFor(post(urlPathEqualTo(hookPath))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        // Register DB row with the same id as the test SPI.
        registerWebhook(auth, hookId, "http://localhost:" + wiremock.port() + hookPath);

        // (a) LIST precedence: GET shows the DB row (unchanged from pre-F3).
        ResponseEntity<List<Map<String, Object>>> listing = rest.exchange(
                url("/api/v1/extensions"), HttpMethod.GET, new HttpEntity<>(auth), JSON_LIST);
        assertThat(listing.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> matching = listing.getBody().stream()
                .filter(e -> hookId.equals(e.get("id")))
                .toList();
        assertThat(matching)
                .as("LIST precedence: DB row wins — the SPI entry MUST be suppressed for this id")
                .hasSize(1);
        assertThat(matching.get(0).get("type"))
                .as("the surviving entry is the DB row (type=WEBHOOK), not the test SPI (type=NATIVE_SPI)")
                .isEqualTo("WEBHOOK");
        assertThat(matching.get(0).get("url"))
                .as("the surviving entry carries the DB URL — proves it isn't the SPI fallback")
                .isEqualTo("http://localhost:" + wiremock.port() + hookPath);

        // (b) DISPATCH precedence (F3 fix): agent invocation fires the WEBHOOK, NOT the SPI.
        String agentId = createAgentWithPostHook(auth, "E6 precedence agent", hookId);
        fakeModel.respondWith("E6 reply");

        Map<String, Object> runBody = new HashMap<>();
        runBody.put("message", "E6 prompt");
        runBody.put("sessionId", "session-" + UUID.randomUUID());
        ResponseEntity<Map<String, Object>> run = rest.exchange(
                url("/api/agents/" + agentId + "/runs"),
                HttpMethod.POST, new HttpEntity<>(runBody, auth), JSON_MAP);
        assertThat(run.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(E6TestHookSpi.POST_DISPATCH_COUNT.get())
                .as("F3 production fix: the test SPI MUST NOT fire when a DB row exists for the id — SPI is fallback-only now")
                .isEqualTo(0);

        long webhookHits = wiremock.getAllServeEvents().stream()
                .filter(e -> hookPath.equals(e.getRequest().getUrl())).count();
        assertThat(webhookHits)
                .as("F3 production fix: WireMock MUST observe exactly 1 POST — the DB-registered URL wins over the colliding SPI. List view and dispatch path now agree.")
                .isEqualTo(1L);
    }

    @Test
    void spiOnlyNoDbRow_spiStillFires_F3FallbackPreserved() {
        HttpHeaders auth = authenticatedHeaders("ext-spi-fallback-runner");

        // No DB row registered. Agent's postHook id matches the SPI's extensionId.
        String agentId = createAgentWithPostHook(auth, "E6 SPI fallback agent",
                E6TestHookSpi.EXTENSION_ID);
        fakeModel.respondWith("E6 SPI-only reply");

        Map<String, Object> runBody = new HashMap<>();
        runBody.put("message", "E6 SPI-fallback prompt");
        runBody.put("sessionId", "session-" + UUID.randomUUID());
        ResponseEntity<Map<String, Object>> run = rest.exchange(
                url("/api/agents/" + agentId + "/runs"),
                HttpMethod.POST, new HttpEntity<>(runBody, auth), JSON_MAP);
        assertThat(run.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(E6TestHookSpi.POST_DISPATCH_COUNT.get())
                .as("F3 fallback contract: with NO DB row for the id, SPI dispatch MUST still fire — the fallback half of the precedence rule is preserved")
                .isEqualTo(1);
    }

    // ─── fixtures ───

    private void registerWebhook(HttpHeaders auth, String id, String url) {
        Map<String, Object> regBody = new HashMap<>();
        regBody.put("id", id);
        regBody.put("name", "E6 colliding DB row");
        regBody.put("type", "WEBHOOK");
        regBody.put("url", url);
        regBody.put("description", "E6 fixture");
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
        body.put("description", "E6 fixture");
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
        return authenticateAs(username, username + "@test.local", "pass-e6-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }
}
