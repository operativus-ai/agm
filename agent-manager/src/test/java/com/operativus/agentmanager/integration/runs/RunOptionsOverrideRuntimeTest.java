package com.operativus.agentmanager.integration.runs;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModel;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins the per-request {@code RunOptions} override flow from the
 *   {@code POST /api/agents/{agentId}/runs} controller down through
 *   {@link com.operativus.agentmanager.compute.service.AgentClientFactory#buildChatOptions}
 *   to the {@link org.springframework.ai.chat.prompt.ChatOptions} on the outgoing
 *   {@link Prompt}.
 *
 *   <p>{@code RunOptions(temperature, model, systemPrompt, maxTokens, finOpsBoundary)}
 *   resolves with precedence
 *   {@code RunOptions -> Agent default -> GlobalSettings -> hardcoded fallback} per the
 *   buildChatOptions javadoc. Sync, streaming, and background paths all share the same
 *   ChatClient builder; this test covers the sync path as the canonical proof.
 *
 *   <p>Existing {@code SyncRunsRuntimeTest} covers happy-path persistence and 404 contracts
 *   but never asserts that RunOptions actually take effect — a refactor that silently
 *   drops the override (e.g. forgets to pass {@code runOptions} into
 *   {@code buildChatOptions}) would not be caught by any existing runtime test.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class RunOptionsOverrideRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Autowired private FakeChatModel fakeModel;

    @BeforeEach
    void resetFakes() {
        fakeModel.reset();
    }

    @Test
    void temperatureAndMaxTokens_flowFromRunOptionsToChatModelPromptOptions() {
        HttpHeaders auth = authenticatedHeaders("ro-temp");
        String agentId = createAgentViaApi(auth, "RunOptions Probe");
        fakeModel.respondWith("ok");

        Map<String, Object> body = new HashMap<>();
        body.put("message", "test");
        body.put("sessionId", "sess-ro-temp-" + UUID.randomUUID());
        body.put("options", Map.of(
                "temperature", 0.42,
                "maxTokens", 777));

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/agents/" + agentId + "/runs"),
                HttpMethod.POST,
                new HttpEntity<>(body, auth),
                JSON_MAP);

        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "happy path must still 200 even with RunOptions set");
        assertEquals(1, fakeModel.receivedPrompts().size(),
                "single sync run must issue exactly one ChatModel call");

        ChatOptions opts = fakeModel.receivedPrompts().get(0).getOptions();
        assertNotNull(opts, "ChatClient must attach ChatOptions to the Prompt — null means "
                + "AgentClientFactory.buildChatOptions was skipped on this path");
        assertAll("RunOptions overrides reach the ChatModel as ChatOptions",
                () -> assertEquals(0.42, opts.getTemperature(), 0.0001,
                        "temperature override must flow through; got " + opts.getTemperature()),
                () -> assertEquals(777, opts.getMaxTokens().intValue(),
                        "maxTokens override must flow through; got " + opts.getMaxTokens()));
    }

    @Test
    void modelOverride_flowsFromRunOptionsToChatModelPromptOptions() {
        HttpHeaders auth = authenticatedHeaders("ro-model");
        String agentId = createAgentViaApi(auth, "RunOptions Model Probe");
        fakeModel.respondWith("ok");

        // Seed the override model so the FK at agents.model_id is satisfied if the engine
        // ever resolves it; the FakeChatModel doesn't actually pick by name, but the
        // ChatOptions.model() value should still carry through.
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-override', 'gpt-override', 'fake', 'gpt-override', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);

        Map<String, Object> body = new HashMap<>();
        body.put("message", "test");
        body.put("sessionId", "sess-ro-model-" + UUID.randomUUID());
        body.put("options", Map.of("model", "gpt-override"));

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/agents/" + agentId + "/runs"),
                HttpMethod.POST,
                new HttpEntity<>(body, auth),
                JSON_MAP);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        ChatOptions opts = fakeModel.receivedPrompts().get(0).getOptions();
        assertNotNull(opts);
        assertEquals("gpt-override", opts.getModel(),
                "RunOptions.model must override the agent's default model on ChatOptions; "
                        + "got '" + opts.getModel() + "'");
    }

    @Test
    void systemPromptOverride_replacesAgentDefaultSystemMessageInPrompt() {
        HttpHeaders auth = authenticatedHeaders("ro-sys");
        String agentId = createAgentViaApi(auth, "RunOptions SystemPrompt Probe");
        fakeModel.respondWith("ok");

        String overrideSystemPrompt = "Override system prompt sentinel " + UUID.randomUUID();

        Map<String, Object> body = new HashMap<>();
        body.put("message", "test");
        body.put("sessionId", "sess-ro-sys-" + UUID.randomUUID());
        body.put("options", Map.of("systemPrompt", overrideSystemPrompt));

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/agents/" + agentId + "/runs"),
                HttpMethod.POST,
                new HttpEntity<>(body, auth),
                JSON_MAP);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Prompt captured = fakeModel.receivedPrompts().get(0);
        String allMessageText = captured.getInstructions().stream()
                .map(Message::getText)
                .reduce("", (acc, t) -> acc + "\n" + t);
        assertTrue(allMessageText.contains(overrideSystemPrompt),
                "RunOptions.systemPrompt must reach the Prompt's message list. Sentinel "
                        + "'" + overrideSystemPrompt + "' not found in prompt text. Captured: "
                        + allMessageText);
    }

    @Test
    void nullRunOptions_fallsBackToAgentAndGlobalDefaults_noNpe() {
        HttpHeaders auth = authenticatedHeaders("ro-null");
        String agentId = createAgentViaApi(auth, "RunOptions Null Probe");
        fakeModel.respondWith("ok");

        Map<String, Object> body = new HashMap<>();
        body.put("message", "test");
        body.put("sessionId", "sess-ro-null-" + UUID.randomUUID());
        // No "options" key at all — most common case from current FE callers.

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/agents/" + agentId + "/runs"),
                HttpMethod.POST,
                new HttpEntity<>(body, auth),
                JSON_MAP);

        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "absent options must NOT 4xx — null is the canonical fallback path");
        ChatOptions opts = fakeModel.receivedPrompts().get(0).getOptions();
        assertNotNull(opts, "ChatOptions must still be attached even with no RunOptions");
        // Fallback values come from SettingsService.resolveTemperature/resolveTopP which
        // walk: agent default -> global setting -> hardcoded fallback. We don't pin a
        // specific number (would couple to global config), but we DO pin that the
        // resolver returned a usable value rather than null/0.0.
        assertNotNull(opts.getTemperature(),
                "temperature resolver must produce a non-null default; null here means "
                        + "the fallback chain was bypassed");
        assertTrue(opts.getTemperature() >= 0.0 && opts.getTemperature() <= 2.0,
                "default temperature must be within the standard 0.0–2.0 range; got "
                        + opts.getTemperature());
    }

    @Test
    void explicitNullFields_inOptionsObject_fallBackToAgentDefaults() {
        HttpHeaders auth = authenticatedHeaders("ro-explicit-null");
        String agentId = createAgentViaApi(auth, "RunOptions Explicit Null Probe");
        fakeModel.respondWith("ok");

        Map<String, Object> options = new HashMap<>();
        options.put("temperature", null);
        options.put("maxTokens", null);
        options.put("model", null);
        options.put("systemPrompt", null);

        Map<String, Object> body = new HashMap<>();
        body.put("message", "test");
        body.put("sessionId", "sess-ro-explicit-null-" + UUID.randomUUID());
        body.put("options", options);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/agents/" + agentId + "/runs"),
                HttpMethod.POST,
                new HttpEntity<>(body, auth),
                JSON_MAP);

        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "explicit null fields in options must NOT 4xx — RunOptions record allows nulls");
        ChatOptions opts = fakeModel.receivedPrompts().get(0).getOptions();
        // maxTokens override is null -> effectiveMaxTokens is null -> builder.maxTokens(...)
        // not called -> ChatOptions.maxTokens stays null. That's the documented behavior.
        assertEquals(null, opts.getMaxTokens(),
                "explicit null maxTokens must remain null on ChatOptions (the buildChatOptions "
                        + "guard at line 666 skips builder.maxTokens(...) when effectiveMaxTokens "
                        + "is null); got " + opts.getMaxTokens());
        assertNotNull(opts.getTemperature(),
                "explicit null temperature must still fall back to agent/global default");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private HttpHeaders authenticatedHeaders(String label) {
        String username = "ro-" + label + "-" + UUID.randomUUID();
        return authenticateAs(username, username + "@t.local",
                "pwd-ro-1234", List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    private String createAgentViaApi(HttpHeaders auth, String name) {
        // Seed the model first — agents.model_id FK.
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);

        String agentId = "agent-" + UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId);
        body.put("name", name);
        body.put("description", "RunOptions test agent");
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

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/admin/agents"),
                HttpMethod.POST,
                new HttpEntity<>(body, auth),
                JSON_MAP);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode(),
                "fixture precondition: agent must exist; got " + resp.getStatusCode());
        return agentId;
    }
}
