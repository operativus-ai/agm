package com.operativus.agentmanager.integration.finops;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.operativus.agentmanager.control.repository.AlertIntegrationRepository;
import com.operativus.agentmanager.control.service.BudgetExceededAlertBridge;
import com.operativus.agentmanager.core.entity.AlertIntegration;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModel;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: End-to-end "money loop" runtime pin for
 *     {@link BudgetExceededAlertBridge}. Exercises hops 1→5 in one test:
 *     agent run halts mid-flight → {@code GenAiMetricsAdvisor.publishBudgetExceeded}
 *     fires {@code AgentRunEvent(BUDGET_EXCEEDED)} → bridge translates to
 *     {@code AlertFiredEvent} → {@code AlertIntegrationService.onAlertFired}
 *     fans out to per-org webhook → external receiver (WireMock) sees POST.
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 *
 * <p>{@code FinOpsRuntimeTest.budgetHaltPath_emitsBudgetExceededEvent_andSurfacesViaFeed}
 * already pins hops 1-3 (run → BUDGET_EXCEEDED row → feed). This test extends past the
 * feed into the alert-dispatch surface, completing the chain that
 * {@code BudgetAlertsRuntimeTest.alertFiredEventWithSigningSecret_emitsHmacHeadersOnWebhookPost}
 * previously only exercised from a directly-published {@code AlertFiredEvent}.
 */
// Loopback alert-webhook URLs are allowed by the harness default
// (agentmanager.alerts.ssrf.allow-loopback-urls=true in application-test.properties); dropping the
// redundant @TestPropertySource lets this class share the common base context (its imports are
// exactly the base set) instead of forking a dedicated one.
@Tag("integration")
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class BudgetExceededAlertBridgeRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    private static WireMockServer wiremock;

    @Autowired private FakeChatModel fakeModel;
    @Autowired private AlertIntegrationRepository integrationRepository;

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
    void resetState() {
        fakeModel.reset();
        wiremock.resetAll();
        seedModel("gpt-4o-mini");
    }

    private void seedModel(String modelId) {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES (?, ?, 'fake', ?, true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """, modelId, modelId, modelId);
    }

    /**
     * Money-loop end-to-end: an agent run that overruns its budget must surface as a webhook
     * POST at a per-org {@link AlertIntegration}. Asserts the bridge's ruleId
     * ({@code "BUDGET_EXCEEDED"}) and severity ({@code "CRITICAL"}) appear in the dispatched
     * payload, and that the eventId matches the halted run's id (so receivers can dedupe and
     * correlate back to the original run).
     */
    @Test
    void budgetExceeded_dispatchesAlertWebhook_withBridgeRuleIdAndRunIdAsEventId() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String hookPath = "/alerts/bridge-sink-" + tag;
        wiremock.stubFor(post(urlPathEqualTo(hookPath))
                .willReturn(aResponse().withStatus(200).withBody("ok-bridge")));

        HttpHeaders auth = userHeaders("bridge-d1-" + tag);
        String agentId = createAgent(auth, "BudgetExceededAlertBridge probe");
        String orgId = jdbc.queryForObject(
                "SELECT org_id FROM agents WHERE id = ?", String.class, agentId);

        AlertIntegration integ = new AlertIntegration();
        integ.setId("int-bridge-" + tag);
        integ.setName("budget bridge webhook");
        integ.setType("WEBHOOK");
        integ.setEndpointUrl("http://localhost:" + wiremock.port() + hookPath);
        integ.setEnabled(true);
        integ.setOrgId(orgId);
        integrationRepository.save(integ);

        jdbc.update("""
                INSERT INTO finops_valuation_rate (model_id, input_rate_per_k_tokens, output_rate_per_k_tokens, updated_at)
                VALUES ('gpt-4o-mini', 0.15, 0.60, now())
                ON CONFLICT (model_id) DO UPDATE SET
                    input_rate_per_k_tokens = EXCLUDED.input_rate_per_k_tokens,
                    output_rate_per_k_tokens = EXCLUDED.output_rate_per_k_tokens
                """);

        String policyId = "policy-bridge-" + tag;
        jdbc.update("""
                INSERT INTO budget_policies (id, org_id, agent_id, ceiling_usd, active, created_at, updated_at)
                VALUES (?, ?, ?, 0.0001, true, now(), now())
                """, policyId, orgId, agentId);

        fakeModel.respondWithTokens("budget probe reply", 1000, 500);
        ResponseEntity<Map<String, Object>> run = runAgent(
                auth, agentId, "budget probe", "session-bridge-" + tag);
        assertEquals(402, run.getStatusCode().value(),
                "precondition: tiny-ceiling run must halt with 402 PAYMENT_REQUIRED");

        String runId = (String) run.getBody().get("runId");
        assertNotNull(runId, "run response must surface the halted runId for the bridge to use as eventId");

        Awaitility.await("bridge → AlertIntegrationService webhook POST visible")
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> wiremock.getAllServeEvents().stream()
                        .anyMatch(e -> hookPath.equals(e.getRequest().getUrl())));

        var serveEvent = wiremock.getAllServeEvents().stream()
                .filter(e -> hookPath.equals(e.getRequest().getUrl()))
                .findFirst().orElseThrow();
        String body = serveEvent.getRequest().getBodyAsString();
        assertTrue(body.contains("\"" + BudgetExceededAlertBridge.RULE_ID + "\""),
                "webhook body must carry the bridge's ruleId; got: " + body);
        assertTrue(body.contains("\"" + BudgetExceededAlertBridge.SEVERITY + "\""),
                "webhook body must carry the bridge's severity; got: " + body);
        assertTrue(body.contains("\"" + runId + "\""),
                "webhook body must carry the halted runId as eventId (for dedupe + correlation); got: " + body);
    }

    private ResponseEntity<Map<String, Object>> runAgent(HttpHeaders auth, String agentId, String message, String sessionId) {
        Map<String, Object> body = new HashMap<>();
        body.put("message", message);
        body.put("sessionId", sessionId);
        return rest.exchange(url("/api/agents/" + agentId + "/runs"),
                HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);
    }

    private String createAgent(HttpHeaders auth, String name) {
        String agentId = "agent-" + UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId);
        body.put("name", name);
        body.put("description", "BudgetExceededAlertBridge fixture");
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

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/admin/agents"), HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);
        assertEquals(201, response.getStatusCode().value(),
                "fixture precondition: agent create must return 201 before bridge test exercises a run");
        return agentId;
    }

    private HttpHeaders userHeaders(String username) {
        return authenticateAs(username, username + "@test.local", "pass-bridge-1234", List.of("ROLE_USER", "ROLE_ADMIN"));
    }
}
