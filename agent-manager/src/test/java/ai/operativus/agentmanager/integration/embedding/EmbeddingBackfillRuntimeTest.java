package ai.operativus.agentmanager.integration.embedding;

import ai.operativus.agentmanager.control.dto.EmbeddingBackfillResponse;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Real-pgvector + HTTP-authz coverage for the embedding-backfill admin
 *   endpoint ({@code POST /api/v1/admin/embeddings/backfill}). Owns the endpoint's authz contract
 *   (the {@code focused:} tag in {@code AdminEndpointCoverageArchTest}) and proves the end-to-end
 *   re-embed: a row seeded with a zero vector is rebuilt in place (same id, non-zero vector) by the
 *   {@code FakeEmbeddingModel} (768-dim, matching the store) through the real {@code PgVectorStore}
 *   upsert — the path the unit test can't exercise because it mocks the {@code VectorStore}.
 * State: Stateless.
 */
@Import(FakeEmbeddingModelConfig.class)
class EmbeddingBackfillRuntimeTest extends BaseIntegrationTest {

    private static final String BACKFILL = "/api/v1/admin/embeddings/backfill?storeType=KB";

    private String seedZeroVectorKbRow(String orgId, String content) {
        String id = UUID.randomUUID().toString();
        String metadata = "{\"storeType\":\"KB\",\"orgId\":\"" + orgId + "\"}";
        jdbc.update(
                "INSERT INTO vector_store (id, content, metadata, embedding) "
                        + "VALUES (?::uuid, ?, ?::jsonb, "
                        + "('[' || array_to_string(array_fill(0, ARRAY[768]), ',') || ']')::vector)",
                id, content, metadata);
        return id;
    }

    @Test
    void admin_reembedsZeroVectorRowInPlaceUnderCurrentModel() {
        String orgId = "org-emb-" + UUID.randomUUID();
        HttpHeaders admin = registerLoginWithOrg("emb-admin-" + UUID.randomUUID(), orgId);
        String vid = seedZeroVectorKbRow(orgId, "Billing refunds are processed within five business days.");

        ResponseEntity<EmbeddingBackfillResponse> resp =
                authorizedPost(BACKFILL, null, admin, EmbeddingBackfillResponse.class);

        assertEquals(200, resp.getStatusCode().value());
        assertNotNull(resp.getBody());
        assertEquals(orgId, resp.getBody().orgId());
        assertEquals(1, resp.getBody().scanned());
        assertEquals(1, resp.getBody().reembedded());
        assertEquals(768, resp.getBody().dimensions());

        // In-place upsert: the row survives with its id, and its vector is now real (non-zero).
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM vector_store WHERE id = ?::uuid", Integer.class, vid);
        assertEquals(1, count, "re-embed must upsert in place — not duplicate or drop the row");
        String emb = jdbc.queryForObject(
                "SELECT embedding::text FROM vector_store WHERE id = ?::uuid", String.class, vid);
        assertTrue(emb.chars().anyMatch(c -> c >= '1' && c <= '9'),
                "embedding was a zero-vector before backfill; it must be non-zero after re-embed");
    }

    @Test
    void nonAdmin_isForbidden() {
        String u = "emb-user-" + UUID.randomUUID();
        HttpHeaders user = authenticateAs(u, u + "@test.local", "pass-emb-1234", List.of("ROLE_USER"));

        ResponseEntity<String> resp = authorizedPost(BACKFILL, null, user, String.class);

        assertEquals(403, resp.getStatusCode().value());
    }
}
