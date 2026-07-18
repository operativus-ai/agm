package com.operativus.agentmanager.integration.models;

import com.operativus.agentmanager.control.security.OutboundApiKeyConverter;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Black-box runtime coverage of the two {@code /api/models} endpoints
 *   that {@link ModelsRuntimeTest} does not exercise:
 *   {@code POST /api/models/{id}/test} ({@code pingExistingModel}) and
 *   {@code POST /api/models/{id}/clone} ({@code cloneModel}).
 *
 *   <p>Pins:
 *   <ul>
 *     <li>Ping happy path — existing FAKE-provider model → 200 + {@code available=true} +
 *         non-null latencyMs + null errorMessage.</li>
 *     <li>Ping unknown id — current behavior is {@code BusinessValidationException} → 400,
 *         NOT 200 + {@code available=false}. The controller's doc-block says failure is
 *         encoded in the body, but {@code pingExistingModel}'s {@code findById().orElseThrow}
 *         fires BEFORE the {@code pingEntity} catch-all, so unknown-id is a 400. Pinning the
 *         ACTUAL behavior surfaces this doc/impl drift for a future contract decision.</li>
 *     <li>Ping ROLE_USER — 403 RBAC pin.</li>
 *     <li>Clone default name — {@code "<source> (Clone)"} suffix + carry-over of provider,
 *         baseUrl, modelName, capabilities, rateLimitRpm; {@code apiKey} decrypts to the
 *         same plaintext as source.</li>
 *     <li>Clone custom name — {@code newName} query param overrides default.</li>
 *     <li>Clone unknown source — 404 via {@code ResourceNotFoundException} →
 *         {@code GlobalExceptionHandler}.</li>
 *     <li>Clone name collision — 400 via {@code BusinessValidationException} when the
 *         resolved clone name already exists.</li>
 *     <li>Clone ROLE_USER — 403 RBAC pin.</li>
 *   </ul>
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class})
public class ModelsTestAndCloneRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Autowired private OutboundApiKeyConverter apiKeyConverter;

    @BeforeEach
    void resetStateBeforeTest() {
        truncateDatabase();
    }

    // ─── POST /api/models/{id}/test (pingExistingModel) ─────────────────────

    @Test
    void pingExistingModel_fakeProvider_returns200WithAvailableTrueAndLatencySet() {
        HttpHeaders auth = authenticatedAdminHeaders("models-ping-happy");
        String modelId = seedModelRow("ping-happy", "fake");

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/models/" + modelId + "/test"),
                HttpMethod.POST, new HttpEntity<>(auth), JSON_MAP);

        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "ping against a registered FAKE-provider model must return 200 — a 400 here "
                        + "would mean FakeModelProviderConfig did not register the \"FAKE\" key "
                        + "in providerRegistry and the call fell into the \"No provider\" branch");
        Map<String, Object> body = resp.getBody();
        assertNotNull(body, "response body must not be null");
        assertAll("ping happy DTO",
                () -> assertEquals(modelId, body.get("modelId"),
                        "modelId must echo the pinged model's id"),
                () -> assertEquals(true, body.get("available"),
                        "available=true is the success signal the UI renders; false here would "
                                + "mean the FakeChatModel.call invocation threw and pingEntity's "
                                + "catch-all flipped the result"),
                () -> assertNull(body.get("errorMessage"),
                        "errorMessage must be null on success — a non-null here would render as "
                                + "a misleading error banner in the UI"),
                () -> {
                    Number latency = (Number) body.get("latencyMs");
                    assertNotNull(latency, "latencyMs must be populated regardless of outcome");
                    assertTrue(latency.longValue() >= 0L,
                            "latencyMs must be non-negative; got " + latency);
                });
    }

    @Test
    void pingExistingModel_unknownId_returns404() {
        // Doc/impl drift pin: ModelController's doc-block claims "Always responds 200 OK with
        // a ModelPingResult body — failure is encoded in the response body". But
        // ModelService.pingExistingModel does findById().orElseThrow(ResourceNotFoundException)
        // BEFORE delegating to pingEntity's catch-all. So unknown-id surfaces as 404, not
        // 200 + available=false. The exception type was switched from BVE to RNFE in
        // fix/bve-to-rnfe-model-knowledge-services to align with the rest of the codebase.
        HttpHeaders auth = authenticatedAdminHeaders("models-ping-unknown");

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/models/does-not-exist-" + UUID.randomUUID() + "/test"),
                HttpMethod.POST, new HttpEntity<>(auth), JSON_MAP);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "ping unknown-id must return 404 — ResourceNotFoundException from pingExistingModel");
    }

    @Test
    void pingExistingModel_roleUser_isForbidden403() {
        HttpHeaders userAuth = authenticatedUserHeaders("models-ping-roleuser");
        // Seed the model under admin auth so the row exists when the user attempts to ping.
        String modelId = seedModelRow("ping-rbac", "fake");

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/models/" + modelId + "/test"),
                HttpMethod.POST, new HttpEntity<>(userAuth), JSON_MAP);

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(),
                "ROLE_USER must be 403 — pingExistingModel carries @PreAuthorize(hasRole('ADMIN')) "
                        + "at the service layer. A 200 here would mean the annotation was dropped "
                        + "(or the method advice is no longer wired) and the model-liveness "
                        + "endpoint is now accessible to every authenticated user");
    }

    // ─── POST /api/models/{id}/clone (cloneModel) ───────────────────────────

    @Test
    void cloneModel_defaultName_201WithCloneSuffixAndCarriedOverFields() {
        HttpHeaders auth = authenticatedAdminHeaders("models-clone-default");
        String sourceId = seedModelRow("clone-source", "fake");
        Map<String, Object> sourceRow = jdbc.queryForMap(
                "SELECT name, provider, model_name, base_url, supports_tools, supports_vision, "
                        + "supports_system_instructions, model_type, api_key "
                        + "FROM models WHERE id = ?", sourceId);

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/models/" + sourceId + "/clone"),
                HttpMethod.POST, new HttpEntity<>(auth), JSON_MAP);

        assertEquals(HttpStatus.CREATED, resp.getStatusCode(),
                "clone must return 201 like the create path — a 200 here would mean the "
                        + "controller's ResponseEntity.status(CREATED) was dropped");
        Map<String, Object> cloneDto = resp.getBody();
        assertNotNull(cloneDto);
        String cloneId = (String) cloneDto.get("id");
        String expectedDefaultName = sourceRow.get("name") + " (Clone)";

        Map<String, Object> cloneRow = jdbc.queryForMap(
                "SELECT name, provider, model_name, base_url, supports_tools, supports_vision, "
                        + "supports_system_instructions, model_type, api_key "
                        + "FROM models WHERE id = ?", cloneId);

        String sourcePlaintext = apiKeyConverter.convertToEntityAttribute(
                (String) sourceRow.get("api_key"));
        String clonePlaintext = apiKeyConverter.convertToEntityAttribute(
                (String) cloneRow.get("api_key"));

        assertAll("clone default-name field carry-over",
                () -> assertNotEquals(sourceId, cloneId,
                        "clone must have a fresh UUID — sharing the id would be a PK collision"),
                () -> assertEquals(expectedDefaultName, cloneRow.get("name"),
                        "default clone name format is '<source> (Clone)' per service doc-block"),
                () -> assertEquals(expectedDefaultName, cloneDto.get("name"),
                        "response DTO name must match the persisted name"),
                () -> assertEquals(sourceRow.get("provider"), cloneRow.get("provider")),
                () -> assertEquals(sourceRow.get("model_name"), cloneRow.get("model_name")),
                () -> assertEquals(sourceRow.get("supports_tools"), cloneRow.get("supports_tools")),
                () -> assertEquals(sourceRow.get("supports_vision"), cloneRow.get("supports_vision")),
                () -> assertEquals(sourceRow.get("supports_system_instructions"),
                        cloneRow.get("supports_system_instructions")),
                () -> assertEquals(sourceRow.get("model_type"), cloneRow.get("model_type")),
                () -> assertEquals(sourcePlaintext, clonePlaintext,
                        "api_key plaintext must carry over — the converter decrypts source, "
                        + "service copies plaintext into clone, converter re-encrypts on save"));
    }

    @Test
    void cloneModel_customNewName_201WithProvidedName() {
        HttpHeaders auth = authenticatedAdminHeaders("models-clone-custom");
        String sourceId = seedModelRow("clone-source-custom", "fake");
        String customName = "totally-custom-clone-" + UUID.randomUUID();

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/models/" + sourceId + "/clone?newName=" + customName),
                HttpMethod.POST, new HttpEntity<>(auth), JSON_MAP);

        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        Map<String, Object> body = resp.getBody();
        assertNotNull(body);
        assertEquals(customName, body.get("name"),
                "newName query param must override the default '(Clone)' suffix; got "
                        + body.get("name"));
        String persistedName = jdbc.queryForObject(
                "SELECT name FROM models WHERE id = ?", String.class, (String) body.get("id"));
        assertEquals(customName, persistedName,
                "persisted name must match the response DTO — drift here means the controller "
                        + "and service disagreed on the name resolution");
    }

    @Test
    void cloneModel_unknownSourceId_returns404() {
        HttpHeaders auth = authenticatedAdminHeaders("models-clone-unknown");

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/models/does-not-exist-" + UUID.randomUUID() + "/clone"),
                HttpMethod.POST, new HttpEntity<>(auth), JSON_MAP);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "clone unknown id must be 404 — ResourceNotFoundException → GlobalExceptionHandler. "
                        + "A 400 here would mean the service threw BusinessValidationException "
                        + "instead, blurring the not-found vs invalid-input semantics");
    }

    @Test
    void cloneModel_nameCollisionWithResolvedName_returns400() {
        // Pre-seed the collision target: an existing model named exactly "<source> (Clone)"
        // so the cloneModel service rejects the default name resolution.
        HttpHeaders auth = authenticatedAdminHeaders("models-clone-collision");
        String sourceTag = "collision-src-" + UUID.randomUUID();
        String sourceId = seedNamedModelRow(sourceTag, "fake");
        seedNamedModelRow(sourceTag + " (Clone)", "fake");

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/models/" + sourceId + "/clone"),
                HttpMethod.POST, new HttpEntity<>(auth), JSON_MAP);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                "clone with resolved-name collision must be 400 via BusinessValidationException. "
                        + "A 201 here would mean the existsByName guard was dropped and two "
                        + "rows now share the same name");

        Long countWithSourceName = jdbc.queryForObject(
                "SELECT COUNT(*) FROM models WHERE name = ?", Long.class, sourceTag + " (Clone)");
        assertEquals(1L, countWithSourceName,
                "the collision row must still be the only row with that name — a count > 1 "
                        + "would mean the failed clone was partially persisted");
    }

    @Test
    void cloneModel_roleUser_isForbidden403() {
        HttpHeaders userAuth = authenticatedUserHeaders("models-clone-roleuser");
        String sourceId = seedModelRow("clone-rbac", "fake");

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/models/" + sourceId + "/clone"),
                HttpMethod.POST, new HttpEntity<>(userAuth), JSON_MAP);

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(),
                "ROLE_USER must be 403 — cloneModel carries @PreAuthorize(hasRole('ADMIN')). "
                        + "A 201 here would let any authenticated user duplicate models (and "
                        + "their api_keys) under arbitrary names");
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

    /** Default helper: uses the modelId as the name (always unique via UUID suffix). */
    private String seedModelRow(String nameTag, String provider) {
        String modelId = "model-" + nameTag + "-" + UUID.randomUUID();
        return seedNamedModelRowWithId(modelId, modelId, provider);
    }

    /** Variant that lets the test pick the exact name (for collision pinning). */
    private String seedNamedModelRow(String exactName, String provider) {
        String modelId = "model-" + UUID.randomUUID();
        return seedNamedModelRowWithId(modelId, exactName, provider);
    }

    private String seedNamedModelRowWithId(String modelId, String name, String provider) {
        String encryptedSeedKey = apiKeyConverter.convertToDatabaseColumn("sk-seed-" + modelId);
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, api_key, created_at)
                VALUES (?, ?, ?, ?, true, false, true, 'CHAT', ?, now())
                """, modelId, name, provider, "fake-gpt-" + modelId, encryptedSeedKey);
        return modelId;
    }
}
