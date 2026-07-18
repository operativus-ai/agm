package com.operativus.agentmanager.integration.runs;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModel;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins the failure-isolation contract for the followup-generation
 *   step. When {@code generateFollowups=true} on {@code POST /api/agents/{id}/runs}, the
 *   controller routes through {@code AgentService.run} which:
 *   <ol>
 *     <li>Issues the PRIMARY chat call ({@code client.prompt().call().content()}) and
 *         captures the response into {@code localResponse}.</li>
 *     <li>Inside a {@code try/catch (Exception ex)} block (AgentService:340-348),
 *         issues a SECOND chat call to generate followup questions. Any failure is
 *         logged as a WARN and swallowed — the {@code followUpSuggestions} metadata
 *         key is simply omitted from the response.</li>
 *   </ol>
 *
 *   <p>This isolation contract is critical: a buggy or rate-limited model on the
 *   followup synth path must NOT corrupt the primary response. The FE
 *   {@code chat-api.ts} renders the primary {@code response} first and the followups
 *   chips secondarily — a regression where the followup-failure bubbles up would
 *   collapse the entire chat UX on transient errors.
 *
 *   <p>Two pins:
 *   <ol>
 *     <li>Sync run: primary call succeeds, followup synth throws → response 200 with
 *         the primary text intact and {@code metadata.followUpSuggestions} absent.</li>
 *     <li>Sync run: primary call returns successfully even when followup synth
 *         returns malformed (non-JSON) text — the controller does NOT parse it.</li>
 *   </ol>
 *
 *   <p>Streaming-side equivalent is covered by {@code StreamingRunsRuntimeTest}'s
 *   followups-off case; this PR focuses on the sync surface.
 *
 * State: Stateless. Inherits Testcontainers Postgres + full app context from
 *   {@link BaseIntegrationTest}.
 */
@Tag("integration")
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class FollowupGenerationFailureIsolationRuntimeTest extends BaseIntegrationTest {

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

    // Pin: primary call succeeds, followup synth throws — response 200, primary intact,
    // metadata.followUpSuggestions absent. Catches a regression where the followup
    // try/catch is removed or narrowed.
    @Test
    void syncRunGenerateFollowupsTrue_followupCallThrows_primaryResponseStill200AndFollowupKeyAbsent() {
        HttpHeaders auth = authenticatedHeaders("followup-fail-runner");
        String agentId = createAgentViaApi(auth, "Followup Failure Isolation Agent");

        // Script TWO scripted responses: first succeeds (primary), second throws (synth).
        fakeModel.respondWith("Primary response — the answer the user actually needs.");
        fakeModel.respondWith(p -> {
            throw new RuntimeException("Synthetic followup synthesis failure (rate-limit imitation)");
        });

        Map<String, Object> body = new HashMap<>();
        body.put("message", "Question that needs an answer AND followups.");
        body.put("sessionId", "session-followup-fail-" + UUID.randomUUID());
        body.put("generateFollowups", true);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/agents/" + agentId + "/runs"),
                HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);

        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "primary response must still return 200 when the followup-synth call throws — "
                        + "the try/catch around the followup block (AgentService:340-348) is the "
                        + "isolation contract. A change here means the catch was removed/narrowed.");

        Map<String, Object> respBody = resp.getBody();
        assertNotNull(respBody);
        assertEquals("Primary response — the answer the user actually needs.", respBody.get("content"),
                "primary response text must round-trip unchanged regardless of followup outcome");

        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) respBody.get("metadata");
        if (metadata != null) {
            assertFalse(metadata.containsKey("followUpSuggestions"),
                    "metadata must NOT carry a followUpSuggestions key when the synth call failed — "
                            + "the try/catch swallows the exception WITHOUT writing metadata. A key "
                            + "present here means the controller wrote a default/empty value, which "
                            + "would mislead the FE chips renderer. metadata: " + metadata);
        }

        // FakeChatModel must have observed BOTH calls — confirms the followup path actually
        // fired and the failure happened where expected, not somewhere upstream of the synth.
        assertEquals(2, fakeModel.receivedPrompts().size(),
                "FakeChatModel must have received exactly 2 prompts (1 primary + 1 followup). "
                        + "Fewer means the followup path never fired; more means a retry leaked.");
    }

    // Pin: primary call returns; followup synth returns garbage text (non-JSON-array).
    // The controller stores the raw string in metadata.followUpSuggestions WITHOUT
    // parsing — the FE owns JSON parsing. Catches a regression where the controller
    // accidentally parses + validates the synth response (would 500 the whole run).
    @Test
    void syncRunGenerateFollowupsTrue_followupCallReturnsMalformedText_responseStill200AndKeyPresent() {
        HttpHeaders auth = authenticatedHeaders("followup-garbage-runner");
        String agentId = createAgentViaApi(auth, "Followup Garbage Isolation Agent");

        fakeModel.respondWith("Primary response intact.");
        // Deliberately NOT a JSON array — model misbehavior the FE must tolerate.
        fakeModel.respondWith("Sorry, I can't think of any followups right now :(");

        Map<String, Object> body = new HashMap<>();
        body.put("message", "Question whose followups will be garbage.");
        body.put("sessionId", "session-followup-garbage-" + UUID.randomUUID());
        body.put("generateFollowups", true);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/agents/" + agentId + "/runs"),
                HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);

        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "malformed followup synth must not break the primary response");

        Map<String, Object> respBody = resp.getBody();
        assertNotNull(respBody);
        assertEquals("Primary response intact.", respBody.get("content"));

        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) respBody.get("metadata");
        assertNotNull(metadata, "metadata must be present when followups succeed (even if garbage)");
        Object followups = metadata.get("followUpSuggestions");
        assertNotNull(followups, "followUpSuggestions key must be present on the metadata when "
                + "the synth call returned (even with malformed content) — the FE owns JSON parsing.");
        assertTrue(followups instanceof String && ((String) followups).contains("Sorry"),
                "raw synth response must be stored verbatim — controller must NOT pre-parse + reject. "
                        + "Actual: " + followups);

        assertEquals(2, fakeModel.receivedPrompts().size());
    }

    // ─── helpers ───

    private void seedModel(String modelId) {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES (?, ?, 'fake', ?, true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """, modelId, modelId, modelId);
    }

    private String createAgentViaApi(HttpHeaders auth, String name) {
        String agentId = "agent-" + UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId);
        body.put("name", name);
        body.put("description", "Followup failure isolation fixture");
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
        assertEquals(HttpStatus.CREATED, response.getStatusCode(),
                "fixture precondition: agent must exist before run endpoints reference it");
        return agentId;
    }

    private HttpHeaders authenticatedHeaders(String username) {
        return authenticateAs(username, username + "@test.local", "pass-followup-iso-1234",
                List.of("ROLE_USER", "ROLE_ADMIN"));
    }
}
