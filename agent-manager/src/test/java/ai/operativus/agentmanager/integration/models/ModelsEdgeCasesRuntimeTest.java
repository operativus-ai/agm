package ai.operativus.agentmanager.integration.models;

import ai.operativus.agentmanager.control.security.OutboundApiKeyConverter;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import ai.operativus.agentmanager.integration.support.FakeModelProviderConfig;
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

/**
 * Domain Responsibility: Black-box edge-case coverage of the {@code /api/models} surface
 *   that complements {@link ModelsRuntimeTest} (happy paths + the few negative cases it pins)
 *   and {@link ModelsTestAndCloneRuntimeTest} (the two orphan endpoints). Specifically:
 *   <ul>
 *     <li>PATCH unknown id → 404 ({@code ResourceNotFoundException} →
 *         {@code GlobalExceptionHandler})</li>
 *     <li>PATCH ROLE_USER → 403 ({@code @PreAuthorize(hasRole('ADMIN'))})</li>
 *     <li>DELETE unknown id → 204 (silent success — Spring Data JPA's
 *         {@code deleteById} returns silently for unknown ids; pinning the
 *         current behavior so a future contract flip to 404 is deliberate)</li>
 *     <li>DELETE ROLE_USER → 403</li>
 *     <li>CREATE missing required field → 400 (Bean Validation {@code @NotBlank}
 *         on name/provider/modelName)</li>
 *   </ul>
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class})
public class ModelsEdgeCasesRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Autowired private OutboundApiKeyConverter apiKeyConverter;

    @BeforeEach
    void resetStateBeforeTest() {
        truncateDatabase();
    }

    @Test
    void patchUnknownId_returns404() {
        HttpHeaders auth = authenticatedAdminHeaders("models-patch-404");
        Map<String, Object> body = new HashMap<>();
        body.put("name", "irrelevant-" + UUID.randomUUID());
        body.put("provider", "fake");
        body.put("modelName", "fake-gpt-irrelevant");

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/models/does-not-exist-" + UUID.randomUUID()),
                HttpMethod.PATCH, new HttpEntity<>(body, auth), JSON_MAP);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "PATCH on an unknown id must be 404 — ModelService.updateModel throws "
                        + "ResourceNotFoundException which GlobalExceptionHandler maps to 404. "
                        + "A 400 here would mean the throw flipped to BusinessValidationException; "
                        + "a 200 would mean the service silently created a row, which would be a "
                        + "PATCH-as-upsert semantics violation");
    }

    @Test
    void patchRoleUser_isForbidden403() {
        HttpHeaders userAuth = authenticatedUserHeaders("models-patch-roleuser");
        String modelId = seedModelRow("patch-rbac", "fake");
        Map<String, Object> body = new HashMap<>();
        body.put("name", "irrelevant-" + UUID.randomUUID());
        body.put("provider", "fake");
        body.put("modelName", "fake-gpt-irrelevant");

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/models/" + modelId),
                HttpMethod.PATCH, new HttpEntity<>(body, userAuth), JSON_MAP);

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(),
                "PATCH as ROLE_USER must be 403 — updateModel carries "
                        + "@PreAuthorize(hasRole('ADMIN')). A 200 here would let any "
                        + "authenticated user mutate model api keys / base URLs / capability "
                        + "flags, which is the highest-privilege config surface in the app");
    }

    @Test
    void deleteUnknownId_returns204_silentByDesign() {
        // Pins the current Spring Data JPA behavior: deleteById on an unknown row returns
        // silently rather than throwing EmptyResultDataAccessException (older Spring Data
        // threw; modern is silent). ModelController therefore returns 204 unconditionally.
        // If a future product decision wants idempotent vs strict semantics, this test
        // fires and forces the call.
        HttpHeaders auth = authenticatedAdminHeaders("models-delete-404");

        ResponseEntity<Void> resp = rest.exchange(
                url("/api/models/does-not-exist-" + UUID.randomUUID()),
                HttpMethod.DELETE, new HttpEntity<>(auth), Void.class);

        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode(),
                "DELETE on an unknown id currently returns 204 (silent no-op via Spring Data "
                        + "JPA's deleteById behavior). A 404 here would indicate a deliberate "
                        + "switch to strict-not-found semantics; a 500 would mean the underlying "
                        + "EmptyResultDataAccessException is no longer being swallowed");
    }

    @Test
    void deleteRoleUser_isForbidden403() {
        HttpHeaders userAuth = authenticatedUserHeaders("models-delete-roleuser");
        String modelId = seedModelRow("delete-rbac", "fake");

        ResponseEntity<Void> resp = rest.exchange(
                url("/api/models/" + modelId),
                HttpMethod.DELETE, new HttpEntity<>(userAuth), Void.class);

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(),
                "DELETE as ROLE_USER must be 403 — deleteModel carries "
                        + "@PreAuthorize(hasRole('ADMIN')). A 204 here would let any "
                        + "authenticated user nuke model configurations");

        Long rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM models WHERE id = ?", Long.class, modelId);
        assertEquals(1L, rowCount,
                "the row must still exist — a 403 must not have side-effected the delete");
    }

    @Test
    void createMissingRequiredFields_returns400() {
        // ModelRequest declares @NotBlank on name, provider, modelName. Sending a body with
        // those omitted (or blank) must trigger MethodArgumentNotValidException at the
        // @Valid binding step, mapped to 400 by GlobalExceptionHandler. A 500 here would
        // indicate the GlobalExceptionHandler is no longer wired (or the @Valid annotation
        // was dropped from the controller method).
        HttpHeaders auth = authenticatedAdminHeaders("models-create-novalidate");

        Map<String, Object> body = new HashMap<>();
        body.put("name", "");
        body.put("provider", "");
        body.put("modelName", "");
        body.put("modelType", "CHAT");

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/models"),
                HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                "POST with blank required fields must be 400 via @Valid / "
                        + "MethodArgumentNotValidException — a 201 here would mean the "
                        + "@NotBlank constraints on ModelRequest are no longer wired");

        Long blankNameRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM models WHERE name = '' OR name IS NULL",
                Long.class);
        assertEquals(0L, blankNameRows,
                "no blank-name row may exist post-rejection — a count > 0 would mean the "
                        + "create path partially persisted before validation fired");
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private HttpHeaders authenticatedAdminHeaders(String username) {
        return authenticateAs(username, username + "@test.local", "pass-models-1234",
                List.of("ROLE_ADMIN", "ROLE_USER"));
    }

    private HttpHeaders authenticatedUserHeaders(String username) {
        return authenticateAs(username, username + "@test.local", "pass-models-1234",
                List.of("ROLE_USER"));
    }

    private String seedModelRow(String nameTag, String provider) {
        String modelId = "model-" + nameTag + "-" + UUID.randomUUID();
        String encryptedSeedKey = apiKeyConverter.convertToDatabaseColumn("sk-seed-" + modelId);
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, api_key, created_at)
                VALUES (?, ?, ?, ?, true, false, true, 'CHAT', ?, now())
                """, modelId, modelId, provider, "fake-gpt-" + nameTag, encryptedSeedKey);
        return modelId;
    }
}
