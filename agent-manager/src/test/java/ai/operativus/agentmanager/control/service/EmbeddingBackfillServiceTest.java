package ai.operativus.agentmanager.control.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import ai.operativus.agentmanager.core.exception.BusinessValidationException;
import ai.operativus.agentmanager.core.model.MetadataKeys;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Domain Responsibility: Unit coverage for {@link EmbeddingBackfillService}. Pins the load-bearing
 *   safety contract — <b>refuse before writing</b> when the active embedding model is NoOp (zero
 *   vectors), dimension-mismatched, or probe-failing — so a backfill can never silently overwrite
 *   good vectors with zeros or corrupt the store. Also pins the happy-path re-embed (id + metadata
 *   preserved, scoped read, per-storeType tally). The {@code VectorStore} is mocked, so PgVectorStore's
 *   real upsert is exercised by the sibling {@code EmbeddingBackfillRuntimeTest}.
 * State: Stateless.
 */
class EmbeddingBackfillServiceTest {

    private static final int STORE_DIM = 768;
    private static final String ORG = "org-1";

    private final VectorStore vectorStore = mock(VectorStore.class);
    private final EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private EmbeddingBackfillService service() {
        return new EmbeddingBackfillService(vectorStore, embeddingModel, jdbcTemplate, objectMapper, STORE_DIM);
    }

    private static float[] nonZero(int dim) {
        float[] v = new float[dim];
        for (int i = 0; i < dim; i++) v[i] = 0.01f * (i + 1);
        return v;
    }

    @SuppressWarnings("unchecked")
    private void stubRows(List<Document> rows) {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn((List) rows);
    }

    @Test
    void noOpModel_refusesAndWritesNothing() {
        when(embeddingModel.embed(anyString())).thenReturn(new float[STORE_DIM]); // all zeros

        BusinessValidationException ex = assertThrows(BusinessValidationException.class,
                () -> service().reembedForOrg(ORG, null));
        assertTrue(ex.getMessage().toLowerCase().contains("zero-vector"));
        verify(vectorStore, never()).add(any());
    }

    @Test
    void dimensionMismatch_refusesAndWritesNothing() {
        when(embeddingModel.embed(anyString())).thenReturn(nonZero(512)); // != 768 store dim

        BusinessValidationException ex = assertThrows(BusinessValidationException.class,
                () -> service().reembedForOrg(ORG, null));
        assertTrue(ex.getMessage().contains("512") && ex.getMessage().contains("768"));
        verify(vectorStore, never()).add(any());
    }

    @Test
    void probeThrows_refusesAndWritesNothing() {
        when(embeddingModel.embed(anyString())).thenThrow(new RuntimeException("no api key"));

        BusinessValidationException ex = assertThrows(BusinessValidationException.class,
                () -> service().reembedForOrg(ORG, null));
        assertTrue(ex.getMessage().toLowerCase().contains("probe failed"));
        verify(vectorStore, never()).add(any());
    }

    @Test
    void blankOrg_refuses() {
        assertThrows(BusinessValidationException.class, () -> service().reembedForOrg("  ", null));
        verify(vectorStore, never()).add(any());
    }

    @Test
    void operationalModel_reembedsPreservingIdsAndMetadata() {
        when(embeddingModel.embed(anyString())).thenReturn(nonZero(STORE_DIM));
        Document kb1 = new Document("11111111-1111-1111-1111-111111111111", "kb chunk a",
                Map.of(MetadataKeys.STORE_TYPE, "KB", MetadataKeys.ORG_ID, ORG));
        Document kb2 = new Document("22222222-2222-2222-2222-222222222222", "kb chunk b",
                Map.of(MetadataKeys.STORE_TYPE, "KB", MetadataKeys.ORG_ID, ORG));
        Document mem = new Document("33333333-3333-3333-3333-333333333333", "memory note",
                Map.of(MetadataKeys.STORE_TYPE, "MEMORY", MetadataKeys.ORG_ID, ORG));
        stubRows(List.of(kb1, kb2, mem));

        EmbeddingBackfillService.BackfillResult r = service().reembedForOrg(ORG, null);

        assertEquals(3, r.scanned());
        assertEquals(3, r.reembedded());
        assertEquals(STORE_DIM, r.dimensions());
        assertEquals(2, r.byStoreType().get("KB"));
        assertEquals(1, r.byStoreType().get("MEMORY"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        List<String> ids = captor.getValue().stream().map(Document::getId).toList();
        assertTrue(ids.containsAll(List.of(kb1.getId(), kb2.getId(), mem.getId())),
                "re-add must preserve the original row ids so PgVectorStore upserts in place");
    }

    @Test
    void noMatchingRows_returnsZeroAndDoesNotWrite() {
        when(embeddingModel.embed(anyString())).thenReturn(nonZero(STORE_DIM));
        stubRows(List.of());

        EmbeddingBackfillService.BackfillResult r = service().reembedForOrg(ORG, "KB");

        assertEquals(0, r.scanned());
        assertEquals(0, r.reembedded());
        verify(vectorStore, never()).add(any());
    }
}
