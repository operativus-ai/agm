package ai.operativus.agentmanager.control.dto;

import org.springframework.lang.Nullable;

import java.util.Map;

/**
 * Domain Responsibility: API response for the embedding-backfill admin endpoint. Reports how many
 *   {@code vector_store} rows were scanned and re-embedded under the active model, the model's vector
 *   dimension, and a per-{@code storeType} breakdown.
 * State: Immutable record.
 */
public record EmbeddingBackfillResponse(
        String orgId,
        @Nullable String storeTypeFilter,
        int scanned,
        int reembedded,
        int dimensions,
        Map<String, Integer> byStoreType) {
}
