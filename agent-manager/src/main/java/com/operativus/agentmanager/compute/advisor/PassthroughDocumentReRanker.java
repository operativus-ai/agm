package com.operativus.agentmanager.compute.advisor;

import org.springframework.ai.document.Document;

import java.util.Collections;
import java.util.List;

/**
 * Domain Responsibility: No-op {@link DocumentReRanker} that returns the first {@code topN}
 *   documents from the candidate pool in their original order. The advisor-chain audit
 *   (docs/analysis/agm-advisor-chain-audit.md) found that the LLM-backed reranker
 *   ({@code LlmDocumentReRanker}) was disabled by default
 *   ({@code agent.rag.reranker.enabled=false}) and was never flipped on by any tenant;
 *   shipping an extra LLM call per RAG retrieval producing no consumed value was net
 *   negative. This passthrough preserves the {@link DocumentReRanker} contract that
 *   {@code AdvancedRagAdvisor} depends on without paying the LLM-call cost.
 * State: Stateless.
 *
 * <p>When real reranking is justified (a tenant asks; a benchmark proves the LLM-scored
 * order materially beats positional order for that tenant's KB), reintroduce an
 * {@code LlmDocumentReRanker} or vendor-backed implementation and wire it in
 * {@code AgentClientFactory} alongside the existing positional fallback.
 */
public final class PassthroughDocumentReRanker implements DocumentReRanker {

    @Override
    public List<Document> rerank(String query, List<Document> documents, int topN) {
        if (documents == null || documents.isEmpty()) {
            return Collections.emptyList();
        }
        return documents.subList(0, Math.min(topN, documents.size()));
    }
}
