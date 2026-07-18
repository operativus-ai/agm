package ai.operativus.agentmanager.integration.knowledge;

import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Black-box runtime coverage for
 *   {@link ai.operativus.agentmanager.control.controller.KnowledgePreviewController} —
 *   {@code GET /api/knowledge/{id}/preview} (metadata + chunk-count summary) and
 *   {@code GET /api/knowledge/{id}/chunks/detail} (per-chunk text + metadata). Pins the
 *   wire shape, the 404 path on unknown ids, and the chunks-detail summary structure.
 *   Cross-tenant 404 (existence-leak protection) is exercised by
 *   {@link KnowledgeBaseTenantIsolationRuntimeTest#preview404ForCrossTenantOwnedDocument()}
 *   so this class focuses on single-tenant happy/missing-id behavior only.
 *   Does NOT pin a 409 on {@code PROCESSING} status: the current controller returns 200
 *   with the in-progress entity body. That semantics decision is tracked as a follow-up.
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class KnowledgePreviewRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    @BeforeEach
    void resetState() {
        truncateDatabase();
    }

    @Test
    void previewReturnsDocumentMetadataAndChunkCountForCompletedDocument() {
        HttpHeaders auth = userAuth("kp-preview-user");
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        seedKnowledgeBase(kbId, "kp-test-kb");
        seedKnowledgeContent(docId, kbId, "preview-fixture.txt", "COMPLETED", List.of(UUID.randomUUID(), UUID.randomUUID()));

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/knowledge/" + docId + "/preview"),
                HttpMethod.GET,
                new HttpEntity<>(auth),
                JSON_MAP);

        assertEquals(HttpStatus.OK, response.getStatusCode(),
                "GET /api/knowledge/{id}/preview must succeed for an existing doc");

        Map<String, Object> body = response.getBody();
        assertNotNull(body, "preview body must be non-null");

        // Pin the documented preview wire-shape (KnowledgePreviewController:34-46).
        // Field names are LinkedHashMap-ordered but assertions are order-agnostic.
        assertEquals(docId.toString(), String.valueOf(body.get("id")),
                "preview.id must echo the requested document id");
        assertEquals("preview-fixture.txt", body.get("name"),
                "preview.name must round-trip the persisted name");
        assertEquals("COMPLETED", body.get("status"),
                "preview.status must reflect the persisted RunStatus");
        assertTrue(body.containsKey("contentType"), "preview must include contentType field");
        assertTrue(body.containsKey("uri"), "preview must include uri field");
        assertTrue(body.containsKey("size"), "preview must include size field");
        assertTrue(body.containsKey("metadata"), "preview must include metadata field");
        assertTrue(body.containsKey("createdAt"), "preview must include createdAt field");
        assertTrue(body.containsKey("updatedAt"), "preview must include updatedAt field");

        // chunkCount derives from vector_ids array length (set to 2 above).
        assertEquals(2, ((Number) body.get("chunkCount")).intValue(),
                "preview.chunkCount must equal the persisted vector_ids array length");
    }

    @Test
    void previewReturns404ForUnknownId() {
        HttpHeaders auth = userAuth("kp-preview-404-user");
        UUID unknownId = UUID.randomUUID();

        ResponseEntity<String> response = rest.exchange(
                url("/api/knowledge/" + unknownId + "/preview"),
                HttpMethod.GET,
                new HttpEntity<>(auth),
                String.class);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "GET /api/knowledge/{unknown-id}/preview must return 404 (ResourceNotFoundException)");
    }

    @Test
    void chunksDetailReturnsSummaryShapeForExistingDocument() {
        HttpHeaders auth = userAuth("kp-chunks-user");
        UUID kbId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        seedKnowledgeBase(kbId, "kp-chunks-kb");
        seedKnowledgeContent(docId, kbId, "chunks-fixture.txt", "COMPLETED", List.of());

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/knowledge/" + docId + "/chunks/detail"),
                HttpMethod.GET,
                new HttpEntity<>(auth),
                JSON_MAP);

        assertEquals(HttpStatus.OK, response.getStatusCode(),
                "GET /api/knowledge/{id}/chunks/detail must succeed for an existing doc");

        Map<String, Object> body = response.getBody();
        assertNotNull(body, "chunks-detail body must be non-null");
        assertEquals(docId.toString(), String.valueOf(body.get("documentId")),
                "chunks-detail.documentId must echo the requested document id");
        assertEquals("chunks-fixture.txt", body.get("documentName"),
                "chunks-detail.documentName must round-trip the persisted name");
        assertTrue(body.containsKey("totalChunks"),
                "chunks-detail must include totalChunks field");
        assertTrue(body.containsKey("chunks"),
                "chunks-detail must include the chunks array (may be empty)");
    }

    @Test
    void chunksDetailReturns404ForUnknownId() {
        HttpHeaders auth = userAuth("kp-chunks-404-user");
        UUID unknownId = UUID.randomUUID();

        ResponseEntity<String> response = rest.exchange(
                url("/api/knowledge/" + unknownId + "/chunks/detail"),
                HttpMethod.GET,
                new HttpEntity<>(auth),
                String.class);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "GET /api/knowledge/{unknown-id}/chunks/detail must return 404");
    }

    private HttpHeaders userAuth(String username) {
        return authenticateAs(username, username + "@test.local", "pass-kp-1234",
                List.of("ROLE_USER"));
    }

    private void seedKnowledgeBase(UUID kbId, String name) {
        // Seed under the default system tenant. New users registered via authenticateAs(...)
        // without an explicit org override fall through to DEFAULT_SYSTEM_ORG in the
        // controller's tenant resolver (see KnowledgePreviewController.resolveCallerOrgId),
        // so the KB and the caller align without needing a per-test users.org_id update.
        jdbc.update("""
                INSERT INTO knowledge_bases (id, name, description, owner_id, org_id, created_at, updated_at)
                VALUES (?::uuid, ?, ?, ?, ?, NOW(), NOW())
                """, kbId, name, "test fixture", "kp-test-owner", "DEFAULT_SYSTEM_ORG");
    }

    private void seedKnowledgeContent(UUID id, UUID kbId, String name, String status, List<UUID> vectorIds) {
        UUID[] arr = vectorIds.toArray(new UUID[0]);
        jdbc.update("""
                INSERT INTO knowledge_contents
                  (id, name, description, content_type, uri, content_hash, size, status,
                   status_message, metadata, vector_ids, knowledge_base_id, owner_id, access_count,
                   created_at, updated_at)
                VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?::uuid, ?, ?, NOW(), NOW())
                """,
                id, name, "test fixture", "text/plain", "fixture://" + name, "hash-" + id, 42, status,
                null, "{}", arr, kbId, "kp-test-owner", 0);
    }
}
