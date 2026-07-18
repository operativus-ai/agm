package com.operativus.agentmanager.compute.routing;

import com.operativus.agentmanager.core.model.definitions.AgentDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Domain Responsibility: Strategy-3 replacement when {@code semantic_scoring_enabled=true}.
 *     Embeds the user's message and runs a cosine-similarity search against the {@code
 *     routing_vectors} table for the org's agent descriptions, returning the top match
 *     (subject to similarity threshold).
 *
 *     <p>Uses a just-in-time embedding pattern via {@link AgentEmbeddingService}: at query
 *     time, any active agent in the candidate set whose description is not yet in the
 *     vector store is embedded on the fly. First call after the toggle is enabled pays
 *     the embedding cost for the entire active roster; subsequent calls amortize to zero
 *     except for newly-added agents. Admins can pre-warm via the routing-embeddings
 *     backfill admin endpoint.
 *
 *     <p>Soft-fails on any error: vector-store failure, embedding-model unavailable,
 *     candidate description missing. Returns {@link Optional#empty()} so the resolver
 *     cascade continues to fallback.
 * State: Stateless (Spring singleton)
 */
@Component
public class SemanticAgentScorer {

    private static final Logger log = LoggerFactory.getLogger(SemanticAgentScorer.class);

    private final VectorStore vectorStore;
    private final AgentEmbeddingService embeddingService;
    private final double minSimilarity;

    public SemanticAgentScorer(@Qualifier("routingVectorStore") VectorStore vectorStore,
                                AgentEmbeddingService embeddingService,
                                @Value("${agm.routing.semantic.min-similarity:0.75}") double minSimilarity) {
        this.vectorStore = vectorStore;
        this.embeddingService = embeddingService;
        this.minSimilarity = minSimilarity;
    }

    public Optional<String> scoreAndSelectBest(String orgId,
                                                String message,
                                                List<AgentDefinition> candidates) {
        if (orgId == null || orgId.isBlank() || message == null || message.isBlank()
                || candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        try {
            embeddingService.embedMissing(orgId, candidates);
            return querySimilarity(orgId, message, candidates);
        } catch (Exception ex) {
            log.warn("SemanticAgentScorer failed for org {} (soft-fail to next strategy): {}",
                    orgId, ex.getMessage());
            return Optional.empty();
        }
    }

    private Optional<String> querySimilarity(String orgId, String message, List<AgentDefinition> candidates) {
        SearchRequest req = SearchRequest.builder()
                .query(message)
                .topK(1)
                .similarityThreshold(minSimilarity)
                .filterExpression("orgId == '" + sanitize(orgId) + "'")
                .build();
        List<Document> results = vectorStore.similaritySearch(req);
        if (results == null || results.isEmpty()) {
            return Optional.empty();
        }
        Document top = results.get(0);
        Object aid = top.getMetadata().get("agentId");
        if (!(aid instanceof String agentId)) {
            return Optional.empty();
        }
        // Must be in the active candidate set (roster may have changed since embedding).
        boolean inSet = candidates.stream().anyMatch(c -> agentId.equals(c.id()));
        return inSet ? Optional.of(agentId) : Optional.empty();
    }

    private static String sanitize(String s) {
        return s.replace("'", "''");
    }
}
