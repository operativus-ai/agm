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
 * Domain Responsibility: Pins that the {@code active} flag on an
 *   {@link ai.operativus.agentmanager.core.entity.ExtensionRegistrationEntity} is
 *   re-read by {@link ai.operativus.agentmanager.compute.advisor.ExtensionHookAdvisor#dispatchHook}
 *   per call — no in-memory caching. Toggling the flag in the DB takes effect on
 *   the very next agent run with NO app restart.
 *
 * State: Stateless. Inherits Testcontainers Postgres + full app context from
 *   {@link BaseIntegrationTest}. Per-class WireMock + {@link FakeChatModel}.
 *
 * <p>Existing {@code ExtensionsRuntimeTest} pins static-configuration cases
 * ({@code active=false} at fixture setup short-circuits dispatch). This test pins
 * the DYNAMIC flip: false → true → false toggles in the DB while the advisor's
 * per-dispatch repo lookup picks up each transition immediately. Pinning this
 * means a future caching optimization that broke the propagation would surface
 * here rather than as a stale-state operator complaint.</p>
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class ExtensionActiveFlagFlipRuntimeTest extends BaseIntegrationTest {

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
    void activeFlag_flippedInDb_takesEffectOnNextRun_noRestart() {
        HttpHeaders auth = authenticatedHeaders("ext-flip-runner");

        String hookId = "ext-flip-" + UUID.randomUUID();
        String hookPath = "/hooks/flip-" + hookId;
        wiremock.stubFor(post(urlPathEqualTo(hookPath))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        // Register with active=false initially.
        registerWebhook(auth, hookId, "http://localhost:" + wiremock.port() + hookPath, false);
        String agentId = createAgentWithPostHook(auth, "E7 flip agent", hookId);

        // ── Run 1: active=false → zero dispatches ──
        runAndAssertCompleted(auth, agentId, "first run (inactive)");
        long afterRun1 = countHookPosts(hookPath);
        assertThat(afterRun1)
                .as("run 1 with active=false MUST NOT have dispatched — short-circuit inside dispatchHook")
                .isEqualTo(0L);

        // ── Flip to active=true via direct JDBC (controller has no PUT; admin UI uses POST-as-upsert
        //     or direct repository.save in practice — either way the advisor reads the column per-call). ──
        flipActive(hookId, true);

        runAndAssertCompleted(auth, agentId, "second run (now active)");
        long afterRun2 = countHookPosts(hookPath);
        assertThat(afterRun2 - afterRun1)
                .as("run 2 after flipping active=true MUST have dispatched exactly one POST — advisor's per-call repo lookup must see the flip")
                .isEqualTo(1L);

        // ── Flip back to active=false ──
        flipActive(hookId, false);

        runAndAssertCompleted(auth, agentId, "third run (deactivated again)");
        long afterRun3 = countHookPosts(hookPath);
        assertThat(afterRun3 - afterRun2)
                .as("run 3 after flipping active=false MUST have dispatched ZERO new POSTs — flip-back is also picked up immediately")
                .isEqualTo(0L);
    }

    // ─── fixtures ───

    private void flipActive(String id, boolean active) {
        int updated = jdbc.update("UPDATE extensions SET active = ? WHERE id = ?", active, id);
        assertThat(updated)
                .as("DB row for extension %s must exist before flipping active=%s", id, active)
                .isEqualTo(1);
    }

    private long countHookPosts(String hookPath) {
        return wiremock.getAllServeEvents().stream()
                .filter(e -> hookPath.equals(e.getRequest().getUrl()))
                .count();
    }

    private void runAndAssertCompleted(HttpHeaders auth, String agentId, String marker) {
        Map<String, Object> runBody = new HashMap<>();
        runBody.put("message", "E7 " + marker);
        runBody.put("sessionId", "session-" + UUID.randomUUID());
        fakeModel.respondWith("E7 reply for " + marker);
        ResponseEntity<Map<String, Object>> run = rest.exchange(
                url("/api/agents/" + agentId + "/runs"),
                HttpMethod.POST, new HttpEntity<>(runBody, auth), JSON_MAP);
        assertThat(run.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(run.getBody().get("status")).isEqualTo("COMPLETED");
    }

    private void registerWebhook(HttpHeaders auth, String id, String url, boolean active) {
        Map<String, Object> regBody = new HashMap<>();
        regBody.put("id", id);
        regBody.put("name", "E7 webhook");
        regBody.put("type", "WEBHOOK");
        regBody.put("url", url);
        regBody.put("description", "E7 fixture");
        regBody.put("active", active);
        ResponseEntity<Map<String, Object>> r = rest.exchange(
                url("/api/v1/extensions"), HttpMethod.POST, new HttpEntity<>(regBody, auth),
                new ParameterizedTypeReference<Map<String, Object>>() {});
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private String createAgentWithPostHook(HttpHeaders auth, String name, String hookId) {
        String agentId = "agent-" + UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId);
        body.put("name", name);
        body.put("description", "E7 fixture");
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
        return authenticateAs(username, username + "@test.local", "pass-e7-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }
}
