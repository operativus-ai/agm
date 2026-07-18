package com.operativus.agentmanager.control.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.operativus.agentmanager.core.exception.BusinessValidationException;
import com.operativus.agentmanager.core.model.MetadataKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Domain Responsibility: Re-embeds existing {@code vector_store} rows under the currently-elected
 *   {@code @Primary EmbeddingModel}. Needed whenever the embedding model changes (e.g. switching the
 *   {@code DEFAULT_MODEL_EMBEDDING} off the zero-vector {@code NoOpEmbeddingModel} onto a real
 *   provider, or swapping providers): the chunk text already lives in {@code vector_store.content},
 *   so we rebuild each row's vector in place — no document re-upload required. Each chunk is read,
 *   reconstructed as a {@link Document} preserving its id + metadata, and re-{@code add}ed; the
 *   {@code PgVectorStore} upserts by id, recomputing the embedding with the current model.
 *
 *   <p><b>Refuses before writing</b> if the active model is non-functional — a zero-vector NoOp, a
 *   dimension that mismatches the pgvector column, or a probe error (missing/invalid key). Without
 *   this guard a backfill run would silently overwrite good vectors with zeros or fail mid-batch and
 *   corrupt the store. The probe mirrors {@code EmbeddingHealthIndicator} but runs fresh at request
 *   time so it reflects the live model, not the startup snapshot.
 *
 *   <p>Tenant-scoped: only rows whose {@code orgId} metadata matches the caller's org are touched
 *   (rows without an {@code orgId} — e.g. legacy/system seed data — are out of scope). Synchronous
 *   and admin-triggered; for very large stores a background-job variant would be the next step.
 * State: Stateless.
 */
@Service
public class EmbeddingBackfillService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingBackfillService.class);
    private static final String PROBE_TEXT = "agm embedding backfill probe";
    private static final float ZERO_EPSILON = 1e-9f;
    private static final int BATCH_SIZE = 100;

    private final VectorStore vectorStore;
    private final EmbeddingModel embeddingModel;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final int storeDimensions;

    public EmbeddingBackfillService(VectorStore vectorStore,
                                    EmbeddingModel embeddingModel,
                                    JdbcTemplate jdbcTemplate,
                                    ObjectMapper objectMapper,
                                    @Value("${spring.ai.vectorstore.pgvector.dimension:768}") int storeDimensions) {
        this.vectorStore = vectorStore;
        this.embeddingModel = embeddingModel;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.storeDimensions = storeDimensions;
    }

    /** Outcome of a backfill run. {@code byStoreType} maps each {@code storeType} to its re-embedded count. */
    public record BackfillResult(String orgId, @Nullable String storeTypeFilter, int scanned,
                                 int reembedded, int dimensions, Map<String, Integer> byStoreType) {}

    /**
     * Re-embeds the caller-org's vectors. {@code storeTypeFilter} optionally narrows to one logical
     * store ({@code "KB"} or {@code "MEMORY"}); null/blank re-embeds all of the org's rows.
     *
     * @throws BusinessValidationException if the active embedding model is NoOp / dimension-mismatched
     *         / probe-failing (refused before any write), or the caller org can't be resolved.
     */
    public BackfillResult reembedForOrg(String orgId, @Nullable String storeTypeFilter) {
        if (orgId == null || orgId.isBlank()) {
            throw new BusinessValidationException("Embedding backfill requires a resolvable caller org.");
        }
        int dims = probeOrRefuse();

        List<Document> docs = readScoped(orgId, storeTypeFilter);
        if (docs.isEmpty()) {
            log.info("Embedding backfill: no rows for org '{}' (storeType filter={})", orgId, storeTypeFilter);
            return new BackfillResult(orgId, storeTypeFilter, 0, 0, dims, Map.of());
        }

        Map<String, Integer> byStoreType = new HashMap<>();
        int reembedded = 0;
        for (int i = 0; i < docs.size(); i += BATCH_SIZE) {
            List<Document> batch = docs.subList(i, Math.min(i + BATCH_SIZE, docs.size()));
            // PgVectorStore.add upserts by id → recomputes each embedding with the current @Primary
            // model and overwrites in place. content + metadata are carried through unchanged.
            vectorStore.add(batch);
            for (Document d : batch) {
                String st = String.valueOf(d.getMetadata().getOrDefault(MetadataKeys.STORE_TYPE, "UNKNOWN"));
                byStoreType.merge(st, 1, Integer::sum);
            }
            reembedded += batch.size();
        }
        log.info("Embedding backfill complete: org '{}' re-embedded {} vectors at {} dims (byStoreType={})",
                orgId, reembedded, dims, byStoreType);
        return new BackfillResult(orgId, storeTypeFilter, docs.size(), reembedded, dims, byStoreType);
    }

    /**
     * Probes the live model and returns its dimension, or throws if it must not be used to overwrite
     * vectors. Fresh per request so a model fixed at startup but mis-probed (transient boot blip) is
     * re-evaluated against current reality.
     */
    private int probeOrRefuse() {
        float[] v;
        try {
            v = embeddingModel.embed(PROBE_TEXT);
        } catch (Exception e) {
            throw new BusinessValidationException("Embedding backfill refused: embedding probe failed ("
                    + e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "")
                    + "). Likely a missing or invalid provider key — fix DEFAULT_MODEL_EMBEDDING before re-embedding.");
        }
        if (v == null || !hasNonZero(v)) {
            throw new BusinessValidationException("Embedding backfill refused: the active embedding model returns "
                    + "zero-vectors (NoOp fallback). Configure a real DEFAULT_MODEL_EMBEDDING and restart before "
                    + "re-embedding — running now would overwrite every vector with zeros.");
        }
        if (v.length != storeDimensions) {
            throw new BusinessValidationException("Embedding backfill refused: the active embedding model emits "
                    + v.length + "-dim vectors but the pgvector store is " + storeDimensions + "-dim. Re-embedding "
                    + "would fail or corrupt similarity — align DEFAULT_MODEL_EMBEDDING with the store dimension.");
        }
        return v.length;
    }

    private List<Document> readScoped(String orgId, @Nullable String storeTypeFilter) {
        boolean hasStoreType = storeTypeFilter != null && !storeTypeFilter.isBlank();
        // Metadata-key names are compile-time constants (not user input); the caller-supplied values
        // are bound as parameters, so this is injection-safe.
        String sql = "SELECT id, content, metadata FROM vector_store WHERE metadata->>'" + MetadataKeys.ORG_ID + "' = ?"
                + (hasStoreType ? " AND metadata->>'" + MetadataKeys.STORE_TYPE + "' = ?" : "");
        Object[] params = hasStoreType ? new Object[]{orgId, storeTypeFilter} : new Object[]{orgId};

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            String content = rs.getString("content");
            if (content == null || content.isBlank()) return null;
            Map<String, Object> metadata = parseMetadata(rs.getString("metadata"));
            return new Document(rs.getString("id"), content, metadata);
        }, params).stream().filter(Objects::nonNull).toList();
    }

    private Map<String, Object> parseMetadata(@Nullable String metaJson) {
        if (metaJson == null || metaJson.isEmpty()) return new HashMap<>();
        try {
            return objectMapper.readValue(metaJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Embedding backfill: failed to parse vector_store metadata JSON, using empty map", e);
            return new HashMap<>();
        }
    }

    private static boolean hasNonZero(float[] v) {
        for (float f : v) {
            if (Math.abs(f) > ZERO_EPSILON) return true;
        }
        return false;
    }
}
