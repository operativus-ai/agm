package com.operativus.agentmanager.integration.runs;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModel;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins the gap #19 contract — every synchronous {@code RunResponse}
 *   carries a non-null inline {@code metrics} field populated from the per-run
 *   {@code RunTelemetryAccumulator}. Closes audit row #19 from
 *   {@code docs/analysis/agm-missing-features.md} via the spec at
 *   {@code docs/plans/agm-next-plan.md}.
 *
 * <p><b>Independent ground-truth assertions (rule A18):</b>
 * <ul>
 *   <li>{@code metrics.model} is asserted against the agent's configured {@code modelId}
 *       (the agent definition is independent of the accumulator the production code reads).</li>
 *   <li>{@code metrics.llmCallCount} is asserted against the
 *       {@link FakeChatModel#receivedPrompts()} count — an independent counter the test fake
 *       maintains, NOT the same accumulator the production code reads.</li>
 *   <li>{@code metrics.durationMs} is asserted as {@code >= 0} (not against any DB row).</li>
 *   <li>{@code metrics.inputTokens} / {@code outputTokens} are asserted as null because
 *       {@link FakeChatModel} does not supply Spring AI Usage metadata; the spec's
 *       null-vs-zero rule (A20) means {@code null} = "telemetry not captured."</li>
 * </ul>
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class RunResponseInlineMetricsRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Autowired
    private FakeChatModel fakeModel;

    @BeforeEach
    void resetStateBeforeTest() {
        truncateDatabase();
        seedModel("gpt-4o-mini");
        fakeModel.reset();
    }

    private void seedModel(String modelId) {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES (?, ?, 'fake', ?, true, false, true, 'CHAT', now())
                """, modelId, modelId, modelId);
    }

    /**
     * Canonical happy-path: sync run completes and the response payload includes a non-null
     * {@code metrics} object whose fields match independent ground truth (the agent's
     * configured model, the fake's prompt count, and a non-negative duration).
     */
    @Test
    @SuppressWarnings("unchecked")
    void syncRunResponseCarriesInlineMetricsMatchingFakeChatModelGroundTruth() {
        HttpHeaders auth = authenticatedHeaders("metrics-runner");
        String agentId = createAgentViaApi(auth, "Inline Metrics Agent");

        fakeModel.respondWith("OK");

        Map<String, Object> body = new HashMap<>();
        body.put("message", "Ping the metrics surface.");
        body.put("sessionId", "session-" + UUID.randomUUID());

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/agents/" + agentId + "/runs"),
                HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> payload = response.getBody();
        assertNotNull(payload, "200 response must carry the RunResponse body");

        Map<String, Object> metrics = (Map<String, Object>) payload.get("metrics");
        assertNotNull(metrics, "RunResponse.metrics must be a non-null object on every sync run (gap #19)");

        // Independent ground truth #1: model matches the agent definition's modelId.
        // The agent definition is set via createAgentViaApi (model="gpt-4o-mini") — independent
        // of the RunTelemetryAccumulator the production code reads.
        assertEquals("gpt-4o-mini", metrics.get("model"),
                "metrics.model must match the agent's configured modelId (set via createAgentViaApi)");

        // Independent ground truth #2: llmCallCount matches FakeChatModel's prompt-receipt count.
        // FakeChatModel.receivedPrompts() is an independent counter the fake maintains; the
        // accumulator's incrementLlmCalls is wired from AgentLoggingAdvisor on every chat call.
        // Equality between these two counters validates the wiring without being tautological.
        Number llmCallCount = (Number) metrics.get("llmCallCount");
        assertNotNull(llmCallCount, "llmCallCount must be non-null after at least one LLM call (boxed Integer per A20)");
        assertEquals(fakeModel.receivedPrompts().size(), llmCallCount.intValue(),
                "metrics.llmCallCount must equal FakeChatModel's received-prompt count (independent ground truth)");

        // Independent ground truth #3: durationMs is non-negative.
        Number durationMs = (Number) metrics.get("durationMs");
        assertNotNull(durationMs, "durationMs is always populated (RunTelemetryAccumulator records start time on construction)");
        assertTrue(durationMs.longValue() >= 0,
                "metrics.durationMs must be >= 0; got: " + durationMs);

        // Null-vs-zero (A20): FakeChatModel does not supply Spring AI Usage metadata, so
        // AgentLoggingAdvisor's `addInputTokens` / `addOutputTokens` are never called.
        // RunMetricsBuilder maps `LongAdder.sum() == 0` to null per A20 ("not captured").
        assertNull(metrics.get("inputTokens"),
                "inputTokens must be null when FakeChatModel does not supply Usage metadata — "
                        + "A20 distinguishes 'not captured' (null) from 'captured zero' (0L)");
        assertNull(metrics.get("outputTokens"),
                "outputTokens must be null for the same reason");
        assertNull(metrics.get("reasoningTokens"),
                "reasoningTokens has no production caller of addReasoningTokens today; always null");

        // errorCount / errorType / errorMessage must be null on the success path.
        assertNull(metrics.get("errorCount"), "errorCount must be null on success");
        assertNull(metrics.get("errorType"), "errorType must be null on success");
        assertNull(metrics.get("errorMessage"), "errorMessage must be null on success");
    }

    private String createAgentViaApi(HttpHeaders auth, String name) {
        String agentId = "agent-" + UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId);
        body.put("name", name);
        body.put("description", "Inline metrics test owner");
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
        ResponseEntity<Map<String, Object>> created = rest.exchange(
                url("/api/admin/agents"), HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(),
                "Agent fixture must be created via /api/admin/agents (the only POST handler); "
                        + "a non-201 here means downstream /runs assertions can't be trusted.");
        return agentId;
    }

    private HttpHeaders authenticatedHeaders(String username) {
        return authenticateAs(username, username + "@test.local", "pass-metrics-1234", List.of("ROLE_USER", "ROLE_ADMIN"));
    }
}
