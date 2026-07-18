package com.operativus.agentmanager.integration.extensions;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModel;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
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
 * Domain Responsibility: Pins the PII boundary contract for the extensions surface —
 *   {@link com.operativus.agentmanager.compute.advisor.ExtensionHookAdvisor} at order 15
 *   runs AFTER {@link com.operativus.agentmanager.compute.advisor.PIIAnonymizationAdvisor}
 *   at order 10, so any prompt or response leaving the system via webhook MUST be the
 *   redacted form. Audit F12 moved the advisor's order specifically to close this leak;
 *   no test currently asserts the post-redaction state arrives at the webhook URL.
 *
 * State: Stateless. Inherits Testcontainers Postgres + full app context from
 *   {@link BaseIntegrationTest}. Uses per-class WireMock for the webhook target and
 *   {@link FakeChatModel} so the LLM reply is scriptable.
 *
 * <p>Test mechanics:</p>
 * <ol>
 *   <li>Seed a {@code pii_policies} row with REGEX pattern + {@code REDACT} strategy
 *       (the redaction substitution format is {@code [REDACTED_<POLICY_NAME>]}
 *       per {@code DeterministicNEREngine.scrub}).</li>
 *   <li>Bind the policy to the agent via {@code agent_pii_policies}.</li>
 *   <li>Register a WEBHOOK extension pointing at WireMock.</li>
 *   <li>Run the agent with a prompt containing the literal SSN; assert the captured
 *       webhook body contains NEITHER the raw SSN NOR the bare token, and DOES
 *       contain the redaction marker.</li>
 * </ol>
 *
 * <p>Two cases — pre-hook (request-side redaction) and post-hook (response-side
 * redaction; the LLM reply is configured to echo the SSN, which the output-side PII
 * advisor must scrub before the post-hook fires).</p>
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class ExtensionPiiRedactionRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    private static final String SSN_POLICY_NAME = "SSN_E1";
    private static final String SSN_PATTERN = "\\d{3}-\\d{2}-\\d{4}";
    private static final String SSN_LITERAL = "123-45-6789";
    private static final String REDACTION_MARKER = "[REDACTED_" + SSN_POLICY_NAME + "]";

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
    void preHookWebhook_receivesRedactedPrompt_notRawSsn() {
        HttpHeaders auth = authenticatedHeaders("ext-pii-pre");

        UUID policyId = seedSsnRedactPolicy();
        String hookId = "ext-pii-pre-" + UUID.randomUUID();
        String hookPath = "/hooks/pii-pre-" + hookId;
        wiremock.stubFor(post(urlPathEqualTo(hookPath))
                .willReturn(aResponse().withStatus(200).withBody("ok")));
        registerWebhook(auth, hookId, "http://localhost:" + wiremock.port() + hookPath, true);

        String agentId = createAgentWithPreHook(auth, "E1 PRE redaction agent", hookId);
        bindPolicyToAgent(agentId, policyId);

        fakeModel.respondWith("E1 reply (no PII echoed)");

        String userPrompt = "Charge customer SSN " + SSN_LITERAL + " today";
        runAgent(auth, agentId, userPrompt);

        ServeEvent event = onlyEventFor(hookPath);
        String body = event.getRequest().getBodyAsString();
        assertThat(body)
                .as("pre-hook webhook body MUST be the post-redaction form — order 15 runs after PII advisor (order 10)")
                .doesNotContain(SSN_LITERAL)
                .contains(REDACTION_MARKER);
    }

    /**
     * F1 production fix landed: {@code ExtensionHookAdvisor} now scrubs the LLM reply via
     * {@code OutputPiiScrubber.scrub(...)} (production implementation:
     * {@code PIIAnonymizationAdvisor.scrub}) BEFORE dispatching post-hooks. Post-hook
     * webhook payloads MUST now carry the redacted form of any LLM-echoed PII, closing
     * audit F5's post-hook leak.
     *
     * <p>The previous version of this test asserted the broken behavior as a regression-lock
     * (contains SSN, doesNotContain marker). When F1 lands, both assertions flip to the
     * positive guard documented here.</p>
     */
    @Test
    void postHookWebhook_receivesRedactedLlmReply_F1ProductionFix() {
        HttpHeaders auth = authenticatedHeaders("ext-pii-post");

        UUID policyId = seedSsnRedactPolicy();
        String hookId = "ext-pii-post-" + UUID.randomUUID();
        String hookPath = "/hooks/pii-post-" + hookId;
        wiremock.stubFor(post(urlPathEqualTo(hookPath))
                .willReturn(aResponse().withStatus(200).withBody("ok")));
        registerWebhook(auth, hookId, "http://localhost:" + wiremock.port() + hookPath, true);

        String agentId = createAgentWithPostHook(auth, "E1 POST redaction agent", hookId);
        bindPolicyToAgent(agentId, policyId);

        // LLM "leaks" the SSN in the response. ExtensionHookAdvisor's outputScrubber MUST
        // redact before the webhook fires (F1 fix).
        fakeModel.respondWith("Acknowledged. The SSN " + SSN_LITERAL + " has been recorded.");

        runAgent(auth, agentId, "E1 post-hook redaction prompt");

        ServeEvent event = onlyEventFor(hookPath);
        String body = event.getRequest().getBodyAsString();
        assertThat(body)
                .as("F1 production fix: post-hook payload MUST be the redacted form of the LLM reply (closes audit F5 post-hook leak)")
                .doesNotContain(SSN_LITERAL)
                .contains(REDACTION_MARKER);
    }

    // ─── fixtures ───

    private UUID seedSsnRedactPolicy() {
        UUID policyId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO pii_policies (id, name, description, pattern_type, pattern, scrub_strategy, enabled,
                                          taxonomic_category, compliance_framework, org_id, created_at, updated_at)
                VALUES (?, ?, 'E1 seeded SSN policy', 'REGEX', ?, 'REDACT', true, 'UNCATEGORIZED', 'STANDARD', 'DEFAULT_SYSTEM_ORG', now(), now())
                """, policyId, SSN_POLICY_NAME, SSN_PATTERN);
        return policyId;
    }

    private void bindPolicyToAgent(String agentId, UUID policyId) {
        jdbc.update("INSERT INTO agent_pii_policies (agent_id, policy_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                agentId, policyId);
    }

    private void registerWebhook(HttpHeaders auth, String id, String url, boolean active) {
        Map<String, Object> regBody = new HashMap<>();
        regBody.put("id", id);
        regBody.put("name", "E1 webhook");
        regBody.put("type", "WEBHOOK");
        regBody.put("url", url);
        regBody.put("description", "E1 fixture");
        regBody.put("active", active);
        ResponseEntity<Map<String, Object>> r = rest.exchange(
                url("/api/v1/extensions"), HttpMethod.POST, new HttpEntity<>(regBody, auth), JSON_MAP);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private String createAgentWithPreHook(HttpHeaders auth, String name, String hookId) {
        return createAgent(auth, name, List.of(hookId), List.of());
    }

    private String createAgentWithPostHook(HttpHeaders auth, String name, String hookId) {
        return createAgent(auth, name, List.of(), List.of(hookId));
    }

    private String createAgent(HttpHeaders auth, String name, List<String> preHooks, List<String> postHooks) {
        String agentId = "agent-" + UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId);
        body.put("name", name);
        body.put("description", "E1 fixture");
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
        body.put("postHooks", postHooks);
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/admin/agents"), HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return agentId;
    }

    private void runAgent(HttpHeaders auth, String agentId, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("message", message);
        body.put("sessionId", "session-" + UUID.randomUUID());
        ResponseEntity<Map<String, Object>> run = rest.exchange(
                url("/api/agents/" + agentId + "/runs"),
                HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);
        assertThat(run.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(run.getBody().get("status")).isEqualTo("COMPLETED");
    }

    private ServeEvent onlyEventFor(String hookPath) {
        List<ServeEvent> matching = wiremock.getAllServeEvents().stream()
                .filter(e -> hookPath.equals(e.getRequest().getUrl()))
                .toList();
        assertThat(matching)
                .as("exactly one webhook POST expected for hook path %s", hookPath)
                .hasSize(1);
        return matching.get(0);
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
        return authenticateAs(username, username + "@test.local", "pass-e1-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }
}
