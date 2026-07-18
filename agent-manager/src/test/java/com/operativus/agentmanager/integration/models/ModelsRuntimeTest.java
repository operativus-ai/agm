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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Black-box runtime coverage of the {@code /api/models} surface —
 *   {@link com.operativus.agentmanager.control.controller.ModelController} →
 *   {@link com.operativus.agentmanager.control.service.ModelService}. Pins RBAC (all methods
 *   require {@code hasRole('ADMIN')}), provider validation, name uniqueness, dependent-agent
 *   conflicts on delete, test-connection roundtrips, and the api-key converter storage
 *   contract.
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 *
 * Cases mapped to {@code docs/testing/agm-runtime-testing.md} §18 and
 * {@code docs/testing/agm-runtime-testing-spec.md} T023.
 *
 * Implementation notes / gaps these tests pin:
 *   - Every {@code ModelService} method carries {@code @PreAuthorize("hasRole('ADMIN')")}.
 *     All tests here use the {@code ROLE_ADMIN} role via {@link #authenticatedAdminHeaders};
 *     Spring's {@code hasRole('ADMIN')} strips the {@code ROLE_} prefix and looks up the
 *     authority name, so the stored role must be {@code ROLE_ADMIN}. Case (i) pins the
 *     negative — a {@code ROLE_USER}-only caller gets 403 — so a refactor that drops the
 *     annotation silently still fails this test.
 *   - {@link com.operativus.agentmanager.control.security.OutboundApiKeyConverter} is
 *     applied to {@code ModelEntity.apiKey} via {@code @Convert}. The test profile configures
 *     {@code agm.security.outbound-key-encryption-key} in {@code application-test.properties},
 *     so the converter runs in ENCRYPT mode. Case (b) reads the raw {@code api_key} column via
 *     JDBC, asserts it is NOT the plaintext (wire format {@code v{N}:Base64(IV||CT||Tag)}) and
 *     then routes the ciphertext back through the converter to confirm it decrypts to the
 *     original plaintext. JDBC-seeded rows in helper {@link #seedModelRow} must also store
 *     valid ciphertext — raw plaintext in {@code api_key} would fail decryption on the next
 *     JPA read (listing / delete / etc.).
 *   - The {@code ModelService.providerRegistry} is built from every {@link com.operativus.agentmanager.compute.provider.DynamicModelProvider}
 *     bean discovered in the context, keyed by {@code getProviderKeys()}. With
 *     {@link FakeModelProviderConfig} imported, the only valid provider key for tests is
 *     {@code "FAKE"}. Using anything else on create/update trips a
 *     {@link com.operativus.agentmanager.core.exception.BusinessValidationException} that
 *     the {@code GlobalExceptionHandler} maps to 400 — case (g) pins that.
 *   - {@code ModelController.deleteModel} catches {@code BusinessValidationException} and
 *     returns 409; {@code ModelService.deleteModel} throws that when {@code agentRepository.findByModelId}
 *     returns non-empty. Case (e) pins the 409 + row-still-present invariant.
 *   - {@code GET /api/models} returns a Spring Data {@code Page<ModelDTO>} JSON shape:
 *     {@code {content: [...], totalElements: N, ...}}. Tests navigate through the
 *     {@code content} array. A future switch to an unwrapped list would break the assertion
 *     shape; that's intentional — the change should be deliberate.
 *   - The tests do NOT pin matrix §18 case 6 (ModelApiKeyMigrationService idempotency — requires
 *     toggling the encryption key between two app boots), case 8 (settings-backed SSOT override —
 *     belongs in the T024 Settings suite), or case 9 (multi-tenant — {@code models} table has no
 *     {@code org_id} column today, making the invariant untestable until the schema change lands).
 *     Each of those gaps is documented inline where relevant; carry-forward in T055-T060.
 *   - {@link FakeModelProviderConfig} is imported so the {@code testConnection} happy path
 *     in case (c) resolves a real {@link com.operativus.agentmanager.compute.provider.DynamicModelProvider}
 *     keyed by {@code "FAKE"} and calls {@link com.operativus.agentmanager.integration.support.FakeChatModel#call}.
 *     Without it the call falls through to {@code "No provider registered for ..."} → 400,
 *     not a useful positive pin for the surface.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class})
public class ModelsRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @Autowired private OutboundApiKeyConverter apiKeyConverter;

    @BeforeEach
    void resetStateBeforeTest() {
        truncateDatabase();
    }

    // §18 — Case (a): GET /api/models returns the paginated Page<ModelDTO> of every model
    // persisted in the table. Pins the admin-only listing endpoint and the Page-shape
    // response contract the UI consumes.
    @Test
    void listModelsReturnsPagedDtosForSeededRows() {
        HttpHeaders auth = authenticatedAdminHeaders("models-list-admin");

        String modelA = seedModelRow("chat-a", "fake");
        String modelB = seedModelRow("chat-b", "fake");

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/models"), HttpMethod.GET, new HttpEntity<>(auth), JSON_MAP);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<String, Object> body = resp.getBody();
        assertNotNull(body, "GET /api/models must return a Page payload, not 204");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) body.get("content");
        assertNotNull(content, "Page<ModelDTO> must serialize with a 'content' array — a null here would mean the endpoint switched to a bare List<>, which the UI cannot paginate");

        assertTrue(content.stream().anyMatch(m -> modelA.equals(m.get("id"))),
                "seeded model A must appear in the listing — absence means the @Cacheable populated a stale empty cache (NoOpCacheManager should make that impossible; a regression here implies a cache type change slipped in)");
        assertTrue(content.stream().anyMatch(m -> modelB.equals(m.get("id"))),
                "seeded model B must appear alongside A");
    }

    // §18 — Case (b): POST /api/models persists the row and the api_key column stores
    // AES-256-GCM ciphertext produced by OutboundApiKeyConverter (the test profile configures
    // agm.security.outbound-key-encryption-key, so the converter runs in encrypt mode). Pins
    // the create path + converter wiring end-to-end: the raw column is NOT the plaintext, the
    // wire format carries the v{N}: version prefix, and round-tripping through the converter
    // decrypts back to the original plaintext. A passthrough-mode test profile would flip the
    // ciphertext assertion — that flip would be intentional and caught here.
    @Test
    void createModelPersistsApiKeyThroughOutboundConverter() {
        HttpHeaders auth = authenticatedAdminHeaders("models-create-admin");

        String modelName = "fake-chat-" + UUID.randomUUID();
        Map<String, Object> body = Map.of(
                "name", modelName,
                "provider", "fake",
                "modelName", "fake-gpt",
                "apiKey", "sk-test-1234",
                "modelType", "CHAT"
        );

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/models"), HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);

        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        String createdId = (String) resp.getBody().get("id");
        assertNotNull(createdId, "201 payload must carry the generated UUID id");
        // ModelDTO is intentionally constructed without apiKey — never leak key material
        // through the REST response. A refactor that adds it back must be deliberate.
        org.junit.jupiter.api.Assertions.assertFalse(resp.getBody().containsKey("apiKey"),
                "ModelDTO response must NOT carry apiKey — leaking it through this endpoint would bypass every infrastructure control that redacts keys in logs/traces");

        String persistedApiKey = jdbc.queryForObject(
                "SELECT api_key FROM models WHERE id = ?", String.class, createdId);
        assertNotNull(persistedApiKey, "api_key must be persisted — a null here indicates the converter returned null on encrypt");
        assertNotEquals("sk-test-1234", persistedApiKey,
                "api_key column must NOT match the plaintext — an exact match means either (a) the converter switched to passthrough mode (missing encryption key config), or (b) the @Convert annotation was removed from ModelEntity.apiKey — both are security regressions");
        assertTrue(persistedApiKey.matches("^v\\d+:.+"),
                "ciphertext must carry the v{N}: wire-format version prefix (§22.6 versioned-key rotation). Got: " + persistedApiKey);

        String decrypted = apiKeyConverter.convertToEntityAttribute(persistedApiKey);
        assertEquals("sk-test-1234", decrypted,
                "round-trip through the converter must recover the original plaintext — a mismatch means the encryption key on encrypt does not match the key on decrypt (rotation misconfig) or the IV/tag framing drifted");
    }

    // §18 — Case (c): POST /api/models/test with a fake provider + FakeChatModel round-trips
    // via ModelService.testConnection → DynamicModelProvider.buildChatModel → ChatModel.call.
    // Pins the happy path of the connectivity probe used by the UI's "Test" button.
    @Test
    void testConnectionWithFakeProviderReturnsOk() {
        HttpHeaders auth = authenticatedAdminHeaders("models-test-admin");

        Map<String, Object> body = Map.of(
                "name", "probe-" + UUID.randomUUID(),
                "provider", "fake",
                "modelName", "fake-gpt",
                "apiKey", "sk-test-probe",
                "modelType", "CHAT"
        );

        ResponseEntity<Void> resp = rest.exchange(
                url("/api/models/test"), HttpMethod.POST, new HttpEntity<>(body, auth), Void.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "test-connection against a registered fake provider must return 200 — a 400 here would mean the FakeModelProviderConfig did not register its \"FAKE\" key in the providerRegistry, and the call fell through to the \"No provider registered\" branch");
    }

    // §18 — Case (d): PATCH /api/models/{id} applies partial updates and persists them.
    // Pins the update path: the response reflects the new values AND a fresh GET sees them
    // (no stale cache under NoOpCacheManager; a real cache would be exercised by
    // AgentsCrudRuntimeTest's live-cache pattern).
    @Test
    void patchModelAppliesAndPersistsPartialUpdate() {
        HttpHeaders auth = authenticatedAdminHeaders("models-patch-admin");

        // Seed via API rather than JDBC so we exercise the create path first — this mirrors
        // how a real admin flow would populate the table.
        String originalName = "patchable-" + UUID.randomUUID();
        Map<String, Object> createBody = Map.of(
                "name", originalName, "provider", "fake", "modelName", "fake-gpt-original",
                "apiKey", "sk-orig", "modelType", "CHAT"
        );
        ResponseEntity<Map<String, Object>> create = rest.exchange(
                url("/api/models"), HttpMethod.POST, new HttpEntity<>(createBody, auth), JSON_MAP);
        assertEquals(HttpStatus.CREATED, create.getStatusCode());
        String modelId = (String) create.getBody().get("id");

        // ModelRequest declares @NotBlank on name/provider/modelName, so PATCH cannot be a true
        // "omit unchanged fields" partial — every required field must be present on the wire or
        // MethodArgumentNotValidException 400s before the service ever runs. Repeat the original
        // name/provider alongside the changed fields; this matches the production UI's shape.
        Map<String, Object> patchBody = new HashMap<>();
        patchBody.put("name", originalName);
        patchBody.put("provider", "fake");
        patchBody.put("modelName", "fake-gpt-patched");
        patchBody.put("maxContextTokens", 32000);

        ResponseEntity<Map<String, Object>> patch = rest.exchange(
                url("/api/models/" + modelId), HttpMethod.PATCH, new HttpEntity<>(patchBody, auth), JSON_MAP);
        assertEquals(HttpStatus.OK, patch.getStatusCode());
        assertEquals("fake-gpt-patched", patch.getBody().get("modelName"));
        assertEquals(32000, patch.getBody().get("maxContextTokens"));

        // Re-read via JDBC to confirm the PATCH's @Transactional boundary committed BEFORE the
        // HTTP response flushed. Originally this was a GET /api/models/{id} round-trip, but
        // the singular-GET handler was removed in PRs #506/#507 — the list-endpoint's full-row
        // shape is the only HTTP surface that exposes single-model state today. A raw column
        // read against the persisted row is a stronger contract than a list-and-filter, and
        // matches the JDBC-verification pattern used in ApprovalsRuntimeTest.approveLifecycle*
        // after the same singular-GET cleanup arc.
        String persistedModelName = jdbc.queryForObject(
                "SELECT model_name FROM models WHERE id = ?", String.class, modelId);
        Integer persistedMaxContextTokens = jdbc.queryForObject(
                "SELECT max_context_tokens FROM models WHERE id = ?", Integer.class, modelId);
        assertEquals("fake-gpt-patched", persistedModelName,
                "post-PATCH row must observe the patched modelName — stale value here means "
                        + "the @Transactional boundary did not commit before the HTTP response, "
                        + "or the service returned a DTO copy that drifted from the persisted row");
        assertEquals(32000, persistedMaxContextTokens,
                "post-PATCH row must observe the patched maxContextTokens");
    }

    // §18 — Case (e): DELETE /api/models/{id} on a model with dependent agents returns 409
    // (the controller's catch on BusinessValidationException) and leaves the row intact.
    // Pins the referential-integrity guard in ModelService.deleteModel that checks
    // agentRepository.findByModelId before issuing the delete.
    @Test
    void deleteModelWithDependentAgentsReturnsConflictAndLeavesRowIntact() {
        HttpHeaders auth = authenticatedAdminHeaders("models-delete-admin");

        String modelId = seedModelRow("dependent-target", "fake");
        String agentId = "agent-dep-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, created_at, updated_at)
                VALUES (?, ?, ?, true, now(), now())
                """, agentId, "Agent That References", modelId);

        ResponseEntity<Void> resp = rest.exchange(
                url("/api/models/" + modelId), HttpMethod.DELETE, new HttpEntity<>(auth), Void.class);
        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode(),
                "DELETE on a model with dependents must return 409 — a 500 here would mean BusinessValidationException bled past the controller's catch, a 204 would mean the referential-integrity guard was dropped and dependent agents now point at a missing model");

        Long stillPresent = jdbc.queryForObject(
                "SELECT count(*) FROM models WHERE id = ?", Long.class, modelId);
        assertEquals(1L, stillPresent,
                "the model row must remain in the table after the 409 — if it was deleted, the dependent agent now has a dangling model_id and every run against that agent 500s");
    }

    // §18 — Case (f): DELETE /api/models/{id} on a model with NO dependents returns 204 and
    // removes the row. Pins the happy-path of hard-delete and is the complement to case (e).
    @Test
    void deleteModelWithNoDependentsReturnsNoContentAndRemovesRow() {
        HttpHeaders auth = authenticatedAdminHeaders("models-delete-clean-admin");

        String modelId = seedModelRow("orphan-target", "fake");

        ResponseEntity<Void> resp = rest.exchange(
                url("/api/models/" + modelId), HttpMethod.DELETE, new HttpEntity<>(auth), Void.class);
        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode(),
                "DELETE on an orphan model must return 204 — the happy path for admins reclaiming unused provider entries");

        Long remaining = jdbc.queryForObject(
                "SELECT count(*) FROM models WHERE id = ?", Long.class, modelId);
        assertEquals(0L, remaining, "row must be physically removed — soft-delete here would be a silent schema-policy change");
    }

    // §18 — Case (g): POST /api/models with an unsupported provider returns 400, not 500.
    // The providerRegistry only contains "FAKE" in these tests; any other value trips the
    // BusinessValidationException branch in ModelService.createModel.
    @Test
    void createModelWithUnsupportedProviderReturnsBadRequest() {
        HttpHeaders auth = authenticatedAdminHeaders("models-badprov-admin");

        Map<String, Object> body = Map.of(
                "name", "unsupported-" + UUID.randomUUID(),
                "provider", "nonexistent-provider",
                "modelName", "some-model",
                "apiKey", "sk-nope",
                "modelType", "CHAT"
        );

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/models"), HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                "unknown provider name must surface as 400 via BusinessValidationException → GlobalExceptionHandler — a 500 here means the exception leaked past the handler, a 201 would mean the provider validation was skipped");
    }

    // §18 — Case (h): POST /api/models with a name that already exists returns 400. Pins the
    // uniqueness check via ModelRepository.existsByName; without it two rows could share a
    // name and the UI's "select by name" workflow would become ambiguous.
    @Test
    void createModelWithDuplicateNameReturnsBadRequest() {
        HttpHeaders auth = authenticatedAdminHeaders("models-dup-admin");

        String sharedName = "shared-name-" + UUID.randomUUID();
        Map<String, Object> first = Map.of(
                "name", sharedName, "provider", "fake", "modelName", "fake-gpt",
                "apiKey", "sk-one", "modelType", "CHAT"
        );
        ResponseEntity<Map<String, Object>> firstResp = rest.exchange(
                url("/api/models"), HttpMethod.POST, new HttpEntity<>(first, auth), JSON_MAP);
        assertEquals(HttpStatus.CREATED, firstResp.getStatusCode());

        Map<String, Object> second = Map.of(
                "name", sharedName, "provider", "fake", "modelName", "fake-gpt-variant",
                "apiKey", "sk-two", "modelType", "CHAT"
        );
        ResponseEntity<Map<String, Object>> dupResp = rest.exchange(
                url("/api/models"), HttpMethod.POST, new HttpEntity<>(second, auth), JSON_MAP);
        assertEquals(HttpStatus.BAD_REQUEST, dupResp.getStatusCode(),
                "second POST with the same name must return 400 — allowing duplicates would let two rows hold the same logical identifier, breaking any downstream selector that picks by name");
    }

    // §18 — Case (i): The /api/models surface is ADMIN-only. A ROLE_USER caller (no
    // ROLE_ADMIN grant) is rejected at the @PreAuthorize boundary BEFORE reaching the
    // service body — so the test pins that the call does NOT succeed and does NOT leak
    // the listing body. GlobalExceptionHandler now carries an
    // @ExceptionHandler(AccessDeniedException.class) → 403, so denial surfaces as the
    // idiomatic 403 FORBIDDEN. A 200 here would mean @PreAuthorize was dropped (RBAC
    // regression, critical); a 500 would mean the AccessDeniedException handler was
    // removed.
    @Test
    void listModelsAsNonAdminUserIsRejectedByPreAuthorizeBoundary() {
        HttpHeaders userAuth = authenticateAs("models-reader", "models-reader@test.local",
                "pass-models-1234", List.of("ROLE_USER"));

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                url("/api/models"), HttpMethod.GET, new HttpEntity<>(userAuth), JSON_MAP);

        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode(),
                "ROLE_USER caller is rejected by the @PreAuthorize boundary and mapped to 403 by the AccessDeniedException handler. A 200 here would indicate the @PreAuthorize was dropped (RBAC regression, critical); a 500 would indicate the AccessDeniedException handler was removed.");
        assertTrue(resp.getBody() == null || !resp.getBody().containsKey("content"),
                "non-admin caller must NOT receive a Page payload — the presence of 'content' here would mean the admin listing leaked to ROLE_USER");
    }

    // ─── helpers ───

    private HttpHeaders authenticatedAdminHeaders(String username) {
        // Explicit ROLE_ADMIN so Spring's hasRole('ADMIN') authority check succeeds.
        // ROLE_USER is also granted so any endpoint that cross-checks both authorities
        // (common in other suites when the same test hits both admin and user surfaces)
        // also works through this same token.
        return authenticateAs(username, username + "@test.local", "pass-models-1234",
                List.of("ROLE_ADMIN", "ROLE_USER"));
    }

    /**
     * JDBC-seeds a minimal models row with the NOT NULL columns from {@link com.operativus.agentmanager.core.entity.ModelEntity}:
     * id, name, provider, supports_tools, supports_vision, supports_system_instructions, model_type.
     * All other columns fall to DB defaults. Use this for cases where the test asserts on
     * pre-existing state (listing, delete, etc.) — cases that exercise the create path go
     * through the HTTP API instead.
     *
     * The {@code api_key} column is pre-encrypted via {@link OutboundApiKeyConverter} before
     * insert, because {@link com.operativus.agentmanager.core.entity.ModelEntity#apiKey} has
     * {@code @Convert(converter = OutboundApiKeyConverter.class)} and Hibernate will attempt
     * to decrypt on every subsequent JPA read. Seeding raw plaintext here would cause every
     * entity-load (listing, delete, etc.) to fail with a decryption exception.
     */
    private String seedModelRow(String nameTag, String provider) {
        String modelId = "model-" + nameTag + "-" + UUID.randomUUID();
        String encryptedSeedKey = apiKeyConverter.convertToDatabaseColumn("sk-seed");
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, api_key, created_at)
                VALUES (?, ?, ?, ?, true, false, true, 'CHAT', ?, now())
                """, modelId, modelId, provider, "fake-gpt-" + nameTag, encryptedSeedKey);
        return modelId;
    }
}
