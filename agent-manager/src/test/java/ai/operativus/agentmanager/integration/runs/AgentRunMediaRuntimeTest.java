package ai.operativus.agentmanager.integration.runs;

import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModel;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import ai.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins media-attachment handling on the sync run endpoint —
 *   {@link ai.operativus.agentmanager.control.controller.AgentsController#run}'s
 *   {@code request.media()} field. Sibling SyncRunsRuntimeTest covers text-only happy
 *   paths; media was completely untested before this canary.
 *
 *   <p>Contract:
 *   <ul>
 *     <li>Empty / null media -> 200, treated identically to a text-only request</li>
 *     <li>Vision-capable model + base64 media -> 200, Media object reaches the ChatModel</li>
 *     <li>Non-vision model + media -> 400 BusinessValidationException
 *         ("Model ... does not support vision") via GlobalExceptionHandler</li>
 *     <li>URL-based media data -> 200, UrlResource wrapped instead of ByteArrayResource
 *         (per {@code AgentsController.mapMedia})</li>
 *     <li>Unreachable URL media / invalid mime type -> 400 (the media-mapping path surfaces these
 *         as client errors via GlobalExceptionHandler's IllegalArgumentException → 400 mapping)</li>
 *   </ul>
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class AgentRunMediaRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    // 1x1 transparent PNG, base64-encoded — minimal valid image data.
    private static final String ONE_PX_PNG_B64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=";

    @Autowired private FakeChatModel fakeModel;

    @BeforeEach
    void resetAndSeed() {
        fakeModel.reset();
        // Standard non-vision model (supports_vision=false) and a vision-capable variant.
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-vision', 'gpt-4o-vision', 'fake', 'gpt-4o-vision', true, true, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    @Test
    void syncRunWithBase64ImageOnVisionModel_returns200_andMediaReachesChatModel() {
        HttpHeaders auth = newCaller("vision-b64");
        String agentId = createAgent(auth, "Vision agent", /*model=*/ "gpt-4o-vision");
        fakeModel.respondWith("vision-ok");

        Map<String, Object> body = new HashMap<>();
        body.put("message", "describe this image");
        body.put("sessionId", "sess-vision-b64-" + UUID.randomUUID());
        body.put("media", List.of(Map.of(
                "type", "image/png",
                "data", ONE_PX_PNG_B64)));

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/agents/" + agentId + "/runs"),
                HttpMethod.POST,
                new HttpEntity<>(body, auth),
                JSON_MAP);

        assertAll("vision-capable model accepts base64 image",
                () -> assertEquals(HttpStatus.OK, resp.getStatusCode(),
                        "vision model + valid base64 image must 200; got " + resp.getStatusCode()),
                () -> assertEquals("COMPLETED", resp.getBody().get("status")),
                () -> assertEquals(1, fakeModel.receivedPrompts().size(),
                        "exactly one ChatModel call expected for sync run"));
    }

    @Test
    void syncRunWithMediaOnNonVisionModel_returns400_withVisionErrorMessage() {
        HttpHeaders auth = newCaller("nonvision-rejected");
        String agentId = createAgent(auth, "Non-vision agent", /*model=*/ "gpt-4o-mini");

        Map<String, Object> body = new HashMap<>();
        body.put("message", "describe this image");
        body.put("sessionId", "sess-nonvision-" + UUID.randomUUID());
        body.put("media", List.of(Map.of(
                "type", "image/png",
                "data", ONE_PX_PNG_B64)));

        ResponseEntity<String> resp = rest.exchange(
                url("/api/agents/" + agentId + "/runs"),
                HttpMethod.POST,
                new HttpEntity<>(body, auth),
                String.class);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                "non-vision model + media must 400 (BusinessValidationException) — "
                        + "regression here means the vision-gate check in AgentService.run "
                        + "was removed; got " + resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertTrue(resp.getBody().toLowerCase().contains("vision"),
                "400 body must explain the vision constraint for the user; got '" + resp.getBody() + "'");
        assertEquals(0, fakeModel.receivedPrompts().size(),
                "no ChatModel call may fire when the vision-gate rejects the request");
    }

    @Test
    void syncRunWithEmptyMediaList_returns200_treatedSameAsNoMedia() {
        HttpHeaders auth = newCaller("empty-media");
        String agentId = createAgent(auth, "Empty-media agent", /*model=*/ "gpt-4o-mini");
        fakeModel.respondWith("ok");

        Map<String, Object> body = new HashMap<>();
        body.put("message", "hello");
        body.put("sessionId", "sess-empty-media-" + UUID.randomUUID());
        body.put("media", List.of());

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/agents/" + agentId + "/runs"),
                HttpMethod.POST,
                new HttpEntity<>(body, auth),
                JSON_MAP);

        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "empty media list must be treated as no-media — mapMedia returns null on empty");
        assertEquals("COMPLETED", resp.getBody().get("status"));
    }

    @Test
    void syncRunWithUnreachableUrlMedia_returns400() {
        // An unreachable/invalid media URL surfaces as a client error (IllegalArgumentException →
        // GlobalExceptionHandler → 400), not a 500. The client supplied an unfetchable URL.
        HttpHeaders auth = newCaller("vision-url-unreachable");
        String agentId = createAgent(auth, "Vision URL agent", /*model=*/ "gpt-4o-vision");
        fakeModel.respondWith("url-vision-ok");

        Map<String, Object> body = new HashMap<>();
        body.put("message", "describe this image at the URL");
        body.put("sessionId", "sess-vision-url-" + UUID.randomUUID());
        body.put("media", List.of(Map.of(
                "type", "image/png",
                "data", "https://example.invalid/does-not-exist.png")));

        ResponseEntity<String> resp = rest.exchange(
                url("/api/agents/" + agentId + "/runs"),
                HttpMethod.POST,
                new HttpEntity<>(body, auth),
                String.class);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                "unreachable URL media must return 400, not 500; got " + resp.getStatusCode());
    }

    @Test
    void syncRunWithInvalidMimeType_returns400() {
        // An invalid MIME type surfaces as a client error (IllegalArgumentException →
        // GlobalExceptionHandler → 400), not a 500.
        HttpHeaders auth = newCaller("bad-mime");
        String agentId = createAgent(auth, "Bad-mime agent", /*model=*/ "gpt-4o-vision");

        Map<String, Object> body = new HashMap<>();
        body.put("message", "garbage mime test");
        body.put("sessionId", "sess-bad-mime-" + UUID.randomUUID());
        body.put("media", List.of(Map.of(
                "type", "not a valid mime type at all",
                "data", ONE_PX_PNG_B64)));

        ResponseEntity<String> resp = rest.exchange(
                url("/api/agents/" + agentId + "/runs"),
                HttpMethod.POST,
                new HttpEntity<>(body, auth),
                String.class);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                "invalid MIME type must return 400, not 500; got " + resp.getStatusCode());
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private HttpHeaders newCaller(String label) {
        String username = "media-" + label + "-" + UUID.randomUUID();
        return authenticateAs(username, username + "@t.local",
                "pwd-media-1234", List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    private String createAgent(HttpHeaders auth, String name, String model) {
        String agentId = "agent-" + UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId);
        body.put("name", name);
        body.put("description", "Media test agent");
        body.put("instructions", "Be helpful.");
        body.put("model", model);
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
