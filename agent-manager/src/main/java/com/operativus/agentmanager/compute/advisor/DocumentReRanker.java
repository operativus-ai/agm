package com.operativus.agentmanager.compute.advisor;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * Domain Responsibility: Abstract contract for re-ranking a candidate pool of documents against
 * a user query. Implementations may use local LLM scoring, external API providers (Cohere, Jina),
 * or local ONNX cross-encoder models.
 *
 * State: Stateless (Strategy Interface)
 *
 * @architecture This interface prevents vendor lock-in by decoupling the re-ranking algorithm
 *               from the RAG pipeline. The {@link AdvancedRagAdvisor} depends only on this
 *               interface, and concrete implementations are swapped via Spring configuration.
 */
public interface DocumentReRanker {

    /**
     * @summary Re-ranks a pool of candidate documents by relevance to the given query.
     * @param query     The original user query to score relevance against.
     * @param documents The candidate documents to re-rank.
     * @param topN      The maximum number of top-scoring documents to return.
     * @return An ordered list of the top N most relevant documents.
     */
    List<Document> rerank(String query, List<Document> documents, int topN);
}
