package ai.operativus.agentmanager.compute.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import ai.operativus.agentmanager.compute.advisor.HitlAdvisor;
import ai.operativus.agentmanager.core.exception.BusinessValidationException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import com.github.tomakehurst.wiremock.client.WireMock;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Runtime test for {@link E2BSandboxTool}. Asserts the 6 vectors required
 * by the T1.2 spec (agm-tools-e2b-remote-sandbox.md §7 T006):
 *   (a) success path returns structured {stdout, stderr, exitCode, durationMs}
 *   (b) missing-credential throws BusinessValidationException
 *   (c) timeout returns structured {exitCode: 'TIMEOUT', ...}
 *   (d) Tier 3 HITL gate is wired (HitlAdvisor.requiresHitl returns true for e2b_execute_python)
 *   (e) Output > 1 MB returns structured {exitCode: 'OUTPUT_TOO_LARGE', truncated: true, ...}
 *   (f) Transient 5xx returns {error: 'provider_unavailable', ...} (no internal retry)
 *
 * State: Stateless. Single per-class WireMockServer for fast bring-up.
 *
 * Independent ground truth (A18): WireMock returns canonical E2B-shaped responses; the tool
 * parses them via Jackson. Assertions read the tool's JSON output, not a mirror of source.
 *
 * Per A21: WireMock fixtures should be validated against captured live E2B response on the
 * implementation date. To validate manually, set E2B_API_KEY and run a one-off test against
 * api.e2b.dev; document fixture diff in the PR body.
 */
class E2BSandboxToolRuntimeTest {

    private static WireMockServer wireMock;
    private static WebClient webClient;
    private static ObjectMapper mapper;

    @BeforeAll
    static void startServer() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        WireMock.configureFor("localhost", wireMock.port());
        mapper = new ObjectMapper();
        webClient = WebClient.builder()
                .baseUrl("http://localhost:" + wireMock.port())
                .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024)) // 2 MB so we can receive large bodies in test
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @AfterAll
    static void stopServer() {
        wireMock.stop();
    }

    private E2BSandboxTool toolWithKey(String apiKey) {
        return new E2BSandboxTool(webClient, mapper, apiKey);
    }

    // (a) success path
    @Test
    void successPath_returnsStructuredResult() throws Exception {
        wireMock.resetAll();
        stubFor(post(urlPathEqualTo("/sandboxes/execute"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"stdout\":\"hello\\n\",\"stderr\":\"\",\"exitCode\":0}")));

        String result = toolWithKey("test-key").e2b_execute_python("print('hello')", null);
        JsonNode json = mapper.readTree(result);

        assertEquals("0", json.get("exitCode").asText());
        assertEquals("hello\n", json.get("stdout").asText());
        assertEquals("", json.get("stderr").asText());
        assertTrue(json.has("durationMs"));
        assertFalse(json.get("truncated").asBoolean());
    }

    // (b) missing credential
    @Test
    void missingCredential_throwsBusinessValidation() {
        BusinessValidationException ex = assertThrows(
                BusinessValidationException.class,
                () -> toolWithKey("").e2b_execute_python("print(1)", null));
        assertTrue(ex.getMessage().contains("E2B_API_KEY") || ex.getMessage().contains("E2B API key"));
    }

    // (c) provider-side TIMEOUT
    @Test
    void providerTimeout_returnsStructuredTimeout() throws Exception {
        wireMock.resetAll();
        stubFor(post(urlPathEqualTo("/sandboxes/execute"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"stdout\":\"\",\"stderr\":\"\",\"exitCode\":\"TIMEOUT\",\"timedOut\":true}")));

        String result = toolWithKey("test-key").e2b_execute_python("import time; time.sleep(60)", 5);
        JsonNode json = mapper.readTree(result);

        assertEquals("TIMEOUT", json.get("exitCode").asText());
        assertNotNull(json.get("durationMs"));
        assertEquals("Execution exceeded sandbox timeout", json.get("stderr").asText());
    }

    // (d) Tier 3 HITL wiring — proves the HitlAdvisor static set was extended for this tool
    @Test
    void tier3HitlGateWired_forE2bExecutePython() {
        // Empty SPI providers list pins the static-set fallback semantics. Per audit F8,
        // requiresHitl is now an instance method; the destructive/finops sets are static.
        HitlAdvisor advisor = new HitlAdvisor(
                org.mockito.Mockito.mock(ai.operativus.agentmanager.core.registry.ApprovalOperations.class),
                org.mockito.Mockito.mock(ai.operativus.agentmanager.control.repository.AgentRepository.class),
                org.mockito.Mockito.mock(ai.operativus.agentmanager.control.service.HumanReviewService.class),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                java.util.List.of(), false, java.util.Set.of());
        assertTrue(advisor.requiresHitl("e2b_execute_python"),
                "e2b_execute_python must be in HitlAdvisor.DESTRUCTIVE_TOOLS for Tier 3 HITL gating");
        assertTrue(advisor.requiresHitl("run_python"),
                "run_python (PythonCodeInterpreterTool) is also Tier 3 HITL-gated — added to HitlAdvisor.DESTRUCTIVE_TOOLS in #1216");
    }

    // (e) Output > 1 MB returns OUTPUT_TOO_LARGE (NOT DataBufferLimitException)
    @Test
    void outputTooLarge_returnsTruncatedStructured() throws Exception {
        wireMock.resetAll();
        // Build ~600KB stdout + ~600KB stderr → 1.2 MB combined > 1 MB cap
        String bigStdout = "x".repeat(600 * 1024);
        String bigStderr = "y".repeat(600 * 1024);
        String body = "{\"stdout\":\"" + bigStdout + "\",\"stderr\":\"" + bigStderr + "\",\"exitCode\":0}";
        stubFor(post(urlPathEqualTo("/sandboxes/execute"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));

        String result = toolWithKey("test-key").e2b_execute_python("print('x'*1000000)", null);
        JsonNode json = mapper.readTree(result);

        assertEquals("OUTPUT_TOO_LARGE", json.get("exitCode").asText());
        assertTrue(json.get("truncated").asBoolean());
        assertTrue(json.get("stdout").asText().length() <= E2BSandboxTool.OUTPUT_TRUNCATE_BYTES);
        assertTrue(json.get("stderr").asText().length() <= E2BSandboxTool.OUTPUT_TRUNCATE_BYTES);
    }

    // (f) Transient 5xx returns provider_unavailable (no internal retry)
    @Test
    void transient5xx_returnsProviderUnavailable_noRetry() throws Exception {
        wireMock.resetAll();
        stubFor(post(urlPathEqualTo("/sandboxes/execute"))
                .willReturn(aResponse()
                        .withStatus(503)
                        .withHeader("Retry-After", "12")
                        .withBody("{\"error\":\"degraded\"}")));

        String result = toolWithKey("test-key").e2b_execute_python("print(1)", null);
        JsonNode json = mapper.readTree(result);

        assertEquals("provider_unavailable", json.get("error").asText());
        assertEquals("e2b", json.get("provider").asText());
        assertEquals(503, json.get("statusCode").asInt());
        assertEquals(12, json.get("retryAfterSeconds").asInt());
        // Assert no internal retry occurred — only one POST hit WireMock
        assertEquals(1, wireMock.getServeEvents().getRequests().size(),
                "Tool MUST NOT internally retry on transient 5xx; LLM decides next action");
    }

    // Extra coverage: 4xx returns provider_request_failed (not provider_unavailable)
    @Test
    void clientError_returnsProviderRequestFailed() throws Exception {
        wireMock.resetAll();
        stubFor(post(urlPathEqualTo("/sandboxes/execute"))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withBody("{\"error\":\"unauthorized\"}")));

        String result = toolWithKey("bad-key").e2b_execute_python("print(1)", null);
        JsonNode json = mapper.readTree(result);

        assertEquals("provider_request_failed", json.get("error").asText());
        assertEquals(401, json.get("statusCode").asInt());
        assertNull(json.get("retryAfterSeconds"));
    }

    // Verify the request body shape the tool sends
    @Test
    void requestBody_carriesCodeAndTimeout() throws Exception {
        wireMock.resetAll();
        stubFor(post(urlPathEqualTo("/sandboxes/execute"))
                .withRequestBody(matchingJsonPath("$.code", com.github.tomakehurst.wiremock.client.WireMock.equalTo("print(2+2)")))
                .withRequestBody(matchingJsonPath("$.language", com.github.tomakehurst.wiremock.client.WireMock.equalTo("python")))
                .withRequestBody(matchingJsonPath("$.timeoutSeconds", com.github.tomakehurst.wiremock.client.WireMock.equalTo("60")))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"stdout\":\"4\",\"stderr\":\"\",\"exitCode\":0}")));

        String result = toolWithKey("test-key").e2b_execute_python("print(2+2)", 60);
        JsonNode json = mapper.readTree(result);
        assertEquals("0", json.get("exitCode").asText());
    }
}
