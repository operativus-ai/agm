package ai.operativus.agentmanager.compute.tools.composio;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.http.Fault;
import ai.operativus.agentmanager.compute.advisor.HitlAdvisor;
import ai.operativus.agentmanager.control.repository.ComposioActionConfigRepository;
import ai.operativus.agentmanager.compute.config.ToolConfig;
import ai.operativus.agentmanager.compute.tools.AgentToolComponent;
import ai.operativus.agentmanager.core.callback.AgentContextHolder;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Domain Responsibility: Runtime test for the Composio dynamic tool adapter (R1.2 / N1).
 * Asserts the 15 vectors required by docs/plans/agm-agentos-tool-parity-impl.md §6 Phase 5
 * (T013) — full coverage of registration, error paths, HITL tier wiring, circuit breaker,
 * naming-collision detection, and logging discipline.
 *
 * State: Stateless. Single per-class WireMockServer for fast bring-up.
 *
 * Independent ground truth (A18): WireMock returns canonical Composio-shaped responses
 * (per Phase 0 V-composio-api verdict — base URL backend.composio.dev, X-API-Key auth);
 * the adapter parses them via Jackson and Resilience4j. Assertions read the adapter's
 * JSON output, not a mirror of source.
 *
 * Per A21: WireMock fixtures should be validated against captured live Composio responses
 * — see src/test/resources/composio-fixtures/README.md for the procedure (currently
 * deferred pending API key provisioning).
 */
class ComposioAdapterRuntimeTest {

    private static WireMockServer wireMock;
    private static WebClient webClient;
    private static ObjectMapper mapper;

    private CircuitBreaker breaker;
    private MockEnvironment env;
    private Logger callbackLogger;
    private ListAppender<ILoggingEvent> callbackAppender;

    @BeforeAll
    static void startServer() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        WireMock.configureFor("localhost", wireMock.port());
        mapper = new ObjectMapper();
        webClient = WebClient.builder()
                .baseUrl("http://localhost:" + wireMock.port())
                .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @AfterAll
    static void stopServer() {
        wireMock.stop();
    }

    @BeforeEach
    void freshBreakerAndCapture() {
        wireMock.resetAll();
        breaker = newBreaker(5);
        env = new MockEnvironment();
        env.setProperty("agent.tools.composio.connection-ids.test-org", "conn-test-123");

        callbackLogger = (Logger) LoggerFactory.getLogger(ComposioToolCallback.class);
        callbackAppender = new ListAppender<>();
        callbackAppender.start();
        callbackLogger.addAppender(callbackAppender);
    }

    @AfterEach
    void detachAppender() {
        callbackLogger.detachAppender(callbackAppender);
        callbackAppender.stop();
    }

    private static CircuitBreaker newBreaker(int minCalls) {
        CircuitBreakerConfig cfg = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .minimumNumberOfCalls(minCalls)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .slidingWindowSize(20)
                .build();
        return CircuitBreaker.of("composio-test-" + System.nanoTime(), cfg);
    }

    private ComposioToolCallback callback(String actionName, String apiKey) {
        // Null connectionRepository → callback resolves connectionId via property fallback only.
        // PR-C tests cover the DB-fallback path in ComposioConnectionAdminRuntimeTest.
        return new ComposioToolCallback(actionName, "desc", webClient, mapper, breaker, apiKey, env, null);
    }

    /** Bind orgId via ScopedValue, then invoke the callback. */
    private String invokeWithOrg(ComposioToolCallback cb, String orgId, String input) {
        try {
            return ScopedValue.where(AgentContextHolder.orgId, orgId).call(() -> cb.call(input));
        } catch (Exception e) {
            throw (e instanceof RuntimeException re) ? re : new RuntimeException(e);
        }
    }

    // (a) Provider registers N callbacks at startup
    /**
     * Builds a registry with an empty mock repository so tests boot under the
     * properties-fallback path. Mirrors the legacy 3-arg construction these tests
     * predated Path B PR-A's DB-source-of-truth wiring.
     */
    private static ComposioActionRegistry registryFor(List<String> actions, int max, int warn) {
        var repo = mock(ai.operativus.agentmanager.control.repository.ComposioActionConfigRepository.class);
        when(repo.findByEnabledTrueOrderByActionName()).thenReturn(List.of());
        return new ComposioActionRegistry(repo, actions, max, warn, new SimpleMeterRegistry());
    }

    @Test
    void provider_registersOneCallbackPerEnabledAction() {
        ComposioActionRegistry reg = registryFor(
                List.of("GMAIL_SEND_EMAIL", "NOTION_CREATE_PAGE", "SLACK_POST_MESSAGE"), 50, 25);
        ComposioToolCallbackProvider provider = new ComposioToolCallbackProvider(
                reg, webClient, mapper, breaker, "test-key", env, null);
        ToolCallback[] callbacks = provider.getToolCallbacks();
        assertEquals(3, callbacks.length);
        assertEquals("composio_gmail_send_email", callbacks[0].getToolDefinition().name());
    }

    // (b) Disabled state when API key blank → 0 callbacks
    @Test
    void provider_disabledWhenApiKeyBlank() {
        ComposioActionRegistry reg = registryFor(List.of("GMAIL_SEND_EMAIL"), 50, 25);
        ComposioToolCallbackProvider provider = new ComposioToolCallbackProvider(
                reg, webClient, mapper, breaker, "", env, null);
        assertEquals(0, provider.getToolCallbacks().length);
    }

    // (c) Successful action invoke returns Composio response passthrough
    @Test
    void successfulInvoke_returnsPassthrough() throws Exception {
        stubFor(post(urlPathMatching("/api/v2/actions/.+/execute"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"successful\":true,\"data\":{\"messageId\":\"abc-123\"},\"error\":null}")));

        String result = invokeWithOrg(callback("GMAIL_SEND_EMAIL", "test-key"), "test-org", "{\"to\":\"alice@example.com\"}");
        JsonNode json = mapper.readTree(result);

        assertEquals("composio", json.get("provider").asText());
        assertEquals("GMAIL_SEND_EMAIL", json.get("action").asText());
        assertNotNull(json.get("durationMs"));
        assertEquals("abc-123", json.get("response").get("data").get("messageId").asText());
        assertTrue(json.get("response").get("successful").asBoolean());
    }

    // (d) HITL tier wiring: Tier 1 / Tier 2 / Tier 3 actions resolve correctly
    @Test
    void hitlTierWiring_resolvesTier1_2_3_correctly() {
        ComposioActionConfigRepository tierRepo = mock(ComposioActionConfigRepository.class);
        when(tierRepo.findByEnabledTrueOrderByActionName()).thenReturn(List.of());
        ComposioTierResolver resolver = new ComposioTierResolver(
                List.of("GMAIL_FETCH_EMAILS"),
                List.of("NOTION_DELETE_PAGE"),
                tierRepo);
        // Per audit F8, requiresHitl is an instance method; SPI providers are an instance field.
        // Build the advisor with this resolver and assert against that instance.
        HitlAdvisor advisor = new HitlAdvisor(
                mock(ai.operativus.agentmanager.core.registry.ApprovalOperations.class),
                mock(ai.operativus.agentmanager.control.repository.AgentRepository.class),
                mock(ai.operativus.agentmanager.control.service.HumanReviewService.class),
                new SimpleMeterRegistry(), List.of(resolver), false, java.util.Set.of());

        assertFalse(advisor.requiresHitl("composio_gmail_fetch_emails"),
                "Tier 1 (allow-list) action MUST NOT require HITL");
        assertTrue(advisor.requiresHitl("composio_slack_post_message"),
                "Default Tier 2 (any composio_* action not classified) MUST require HITL");
        assertTrue(advisor.requiresHitl("composio_notion_delete_page"),
                "Tier 3 (deny-list) action MUST require HITL");
        assertFalse(advisor.requiresHitl("read_file"),
                "Non-composio_ tools fall through to static sets — read_file is not in DESTRUCTIVE_TOOLS");
    }

    // (e) Output > 1 MB returns OUTPUT_TOO_LARGE structured (NOT DataBufferLimitException)
    @Test
    void outputTooLarge_returnsTruncatedStructured() throws Exception {
        String big = "x".repeat((int) ComposioToolCallback.OUTPUT_TOTAL_CAP_BYTES + 1024);
        String body = "{\"successful\":true,\"data\":\"" + big + "\"}";
        stubFor(post(urlPathMatching("/api/v2/actions/.+/execute"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body)));

        String result = invokeWithOrg(callback("GMAIL_FETCH_EMAILS", "test-key"), "test-org", "{}");
        JsonNode json = mapper.readTree(result);

        assertEquals("OUTPUT_TOO_LARGE", json.get("exitCode").asText());
        assertTrue(json.get("truncated").asBoolean());
        assertTrue(json.get("body").asText().length() <= ComposioToolCallback.OUTPUT_TRUNCATE_BYTES);
    }

    // (f) Transient 5xx returns provider_unavailable, only 1 POST to WireMock (no retry)
    @Test
    void transient5xx_returnsProviderUnavailable_noRetry() throws Exception {
        stubFor(post(urlPathMatching("/api/v2/actions/.+/execute"))
                .willReturn(aResponse().withStatus(503).withHeader("Retry-After", "12").withBody("{\"error\":\"degraded\"}")));

        String result = invokeWithOrg(callback("GMAIL_SEND_EMAIL", "test-key"), "test-org", "{}");
        JsonNode json = mapper.readTree(result);

        assertEquals("provider_unavailable", json.get("error").asText());
        assertEquals(503, json.get("statusCode").asInt());
        assertEquals(12, json.get("retryAfterSeconds").asInt());
        assertEquals(1, wireMock.getServeEvents().getRequests().size(),
                "Adapter MUST NOT internally retry on transient 5xx; LLM decides next action");
    }

    // (g) 429 returns provider_rate_limited
    @Test
    void rateLimited_returnsStructured() throws Exception {
        stubFor(post(urlPathMatching("/api/v2/actions/.+/execute"))
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "30").withBody("{\"error\":\"too_many_requests\"}")));

        String result = invokeWithOrg(callback("SLACK_POST_MESSAGE", "test-key"), "test-org", "{}");
        JsonNode json = mapper.readTree(result);

        assertEquals("provider_rate_limited", json.get("error").asText());
        assertEquals(429, json.get("statusCode").asInt());
        assertEquals(30, json.get("retryAfterSeconds").asInt());
    }

    // (h) Connection-missing returns connection_missing structured
    @Test
    void connectionMissing_whenOrgUnbound_returnsStructured() throws Exception {
        // No ScopedValue binding for orgId → AgentContextHolder.getOrgId() == null → connection_missing.
        String result = callback("GMAIL_SEND_EMAIL", "test-key").call("{}");
        JsonNode json = mapper.readTree(result);

        assertEquals("connection_missing", json.get("error").asText());
        assertEquals("composio", json.get("provider").asText());
        assertEquals("GMAIL_SEND_EMAIL", json.get("action").asText());
        assertEquals(0, wireMock.getServeEvents().getRequests().size(),
                "connection_missing MUST short-circuit before any HTTP call");
    }

    // (i) Action-not-enabled — registry-level filter prevents callback registration entirely
    @Test
    void actionNotEnabled_excludedFromProviderCallbacks() {
        ComposioActionRegistry reg = registryFor(List.of("GMAIL_SEND_EMAIL"), 50, 25);
        ComposioToolCallbackProvider provider = new ComposioToolCallbackProvider(
                reg, webClient, mapper, breaker, "test-key", env, null);
        ToolCallback[] callbacks = provider.getToolCallbacks();

        assertEquals(1, callbacks.length);
        assertEquals("composio_gmail_send_email", callbacks[0].getToolDefinition().name());
        assertFalse(reg.isEnabled("NOTION_DELETE_PAGE"),
                "An action not in enabled-actions has no callback exposed to the LLM");
    }

    // (j) Logging never includes args, response body, or API key
    @Test
    void logging_neverLeaksArgsBodyOrApiKey() throws Exception {
        String secretArg = "{\"to\":\"victim@example.com\",\"body\":\"VERY-SECRET-CONTENT\"}";
        String responseBody = "{\"successful\":true,\"data\":{\"sensitive\":\"PROTECTED-RESPONSE-VALUE\"}}";
        String apiKey = "sk-live-MUST-NOT-LEAK-12345";
        stubFor(post(urlPathMatching("/api/v2/actions/.+/execute"))
                .willReturn(aResponse().withStatus(200).withBody(responseBody)));

        invokeWithOrg(callback("GMAIL_SEND_EMAIL", apiKey), "test-org", secretArg);
        // also exercise an error path so warn-level logs fire
        wireMock.resetAll();
        stubFor(post(urlPathMatching("/api/v2/actions/.+/execute"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        invokeWithOrg(callback("GMAIL_SEND_EMAIL", apiKey), "test-org", secretArg);

        for (ILoggingEvent ev : callbackAppender.list) {
            String line = ev.getFormattedMessage();
            assertFalse(line.contains("VERY-SECRET-CONTENT"), "log leaked tool args: " + line);
            assertFalse(line.contains("PROTECTED-RESPONSE-VALUE"), "log leaked response body: " + line);
            assertFalse(line.contains("MUST-NOT-LEAK"), "log leaked API key: " + line);
        }
    }

    // (k) Malformed JSON returns provider_response_invalid
    @Test
    void malformedJson_returnsProviderResponseInvalid() throws Exception {
        stubFor(post(urlPathMatching("/api/v2/actions/.+/execute"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("not actually json{{{")));

        String result = invokeWithOrg(callback("GMAIL_SEND_EMAIL", "test-key"), "test-org", "{}");
        JsonNode json = mapper.readTree(result);

        assertEquals("provider_response_invalid", json.get("error").asText());
        assertEquals("composio", json.get("provider").asText());
    }

    // (l) Network failure (Fault.CONNECTION_RESET_BY_PEER) returns provider_unreachable
    @Test
    void networkFailure_returnsProviderUnreachable() throws Exception {
        stubFor(post(urlPathMatching("/api/v2/actions/.+/execute"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        String result = invokeWithOrg(callback("GMAIL_SEND_EMAIL", "test-key"), "test-org", "{}");
        JsonNode json = mapper.readTree(result);

        assertEquals("provider_unreachable", json.get("error").asText());
        assertEquals("composio", json.get("provider").asText());
        assertNotNull(json.get("cause"));
    }

    // (m) Circuit breaker OPEN after 5 consecutive 503s; 6th call short-circuits without WireMock POST
    @Test
    void circuitBreaker_opensAfterFailureThreshold() throws Exception {
        stubFor(post(urlPathMatching("/api/v2/actions/.+/execute"))
                .willReturn(aResponse().withStatus(503).withBody("{\"error\":\"degraded\"}")));

        ComposioToolCallback cb = callback("GMAIL_SEND_EMAIL", "test-key");
        for (int i = 0; i < 5; i++) {
            invokeWithOrg(cb, "test-org", "{}");
        }
        // 6th call should short-circuit
        String result = invokeWithOrg(cb, "test-org", "{}");
        JsonNode json = mapper.readTree(result);

        assertEquals("provider_circuit_open", json.get("error").asText());
        assertEquals(30, json.get("retryAfterSeconds").asInt());
        assertEquals(5, wireMock.getServeEvents().getRequests().size(),
                "Once breaker is OPEN, subsequent calls must NOT hit the network");
    }

    // (n) Tool-name collision — registering a non-Composio @AgentToolComponent tool with composio_ prefix logs WARN
    @Test
    void toolNameCollision_logsWarn() {
        Logger toolConfigLogger = (Logger) LoggerFactory.getLogger(ToolConfig.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        toolConfigLogger.addAppender(appender);
        try {
            ApplicationContext ctx = mock(ApplicationContext.class);
            Map<String, Object> beans = new HashMap<>();
            beans.put("collidingTool", new CollidingNativeTool());
            when(ctx.getBeansWithAnnotation(AgentToolComponent.class)).thenReturn(beans);

            ComposioToolCallbackProvider emptyProvider = new ComposioToolCallbackProvider(
                    registryFor(List.of(), 50, 25), webClient, mapper, breaker, "", env, null);
            new ToolConfig().globalToolProvider(ctx, emptyProvider);

            assertThat(appender.list)
                    .anyMatch(ev -> ev.getLevel() == Level.WARN
                            && ev.getFormattedMessage().contains("composio_evil_native")
                            && ev.getFormattedMessage().contains("RESERVED"));
        } finally {
            toolConfigLogger.detachAppender(appender);
            appender.stop();
        }
    }

    // (o) 51 enabled-actions registers exactly 50, logs ERROR
    @Test
    void overCap_truncatesToHardCapAndLogsError() {
        Logger registryLogger = (Logger) LoggerFactory.getLogger(ComposioActionRegistry.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        registryLogger.addAppender(appender);
        try {
            java.util.List<String> fiftyOne = new java.util.ArrayList<>();
            for (int i = 0; i < 51; i++) {
                fiftyOne.add("ACTION_" + i);
            }
            ComposioActionRegistry reg = registryFor(fiftyOne, 50, 25);

            assertEquals(50, reg.getEnabledCount());
            assertTrue(reg.wasTruncated());
            assertThat(appender.list)
                    .anyMatch(ev -> ev.getLevel() == Level.ERROR
                            && ev.getFormattedMessage().contains("hard cap"));
        } finally {
            registryLogger.detachAppender(appender);
            appender.stop();
        }
    }

    /** Audit Finding 4 fixture: a native @AgentToolComponent that illegally uses the composio_ prefix. */
    @AgentToolComponent
    static class CollidingNativeTool {
        @Tool(name = "composio_evil_native", description = "should not register")
        public String stealNamespace(String input) {
            return "noop";
        }
    }
}
