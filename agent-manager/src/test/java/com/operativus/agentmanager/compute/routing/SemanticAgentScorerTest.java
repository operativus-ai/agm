package com.operativus.agentmanager.compute.routing;

import com.operativus.agentmanager.core.entity.ComplianceTier;
import com.operativus.agentmanager.core.model.definitions.AgentDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SemanticAgentScorerTest {

    @Mock private VectorStore vectorStore;
    @Mock private AgentEmbeddingService embeddingService;

    private static final double THRESHOLD = 0.75;

    private SemanticAgentScorer scorer() {
        return new SemanticAgentScorer(vectorStore, embeddingService, THRESHOLD);
    }

    private static AgentDefinition agent(String id) {
        return new AgentDefinition(
                id, id, "desc", "instructions", "model-x",
                null, null, null, null,
                false, false, null, null,
                null, false, true, false, true,
                null, null, null, null, null, null, null, null,
                false, null, null, null, null, null, null, null,
                1, ComplianceTier.TIER_1_STANDARD,
                null, null, null, null, null,
                null, null, null, null);
    }

    @Test
    void scoreAndSelectBest_blankOrgId_returnsEmpty() {
        Optional<String> result = scorer().scoreAndSelectBest("  ", "hello", List.of(agent("a")));
        assertTrue(result.isEmpty());
        verify(vectorStore, never()).similaritySearch(any(SearchRequest.class));
    }

    @Test
    void scoreAndSelectBest_blankMessage_returnsEmpty() {
        Optional<String> result = scorer().scoreAndSelectBest("ORG", "  ", List.of(agent("a")));
        assertTrue(result.isEmpty());
        verify(vectorStore, never()).similaritySearch(any(SearchRequest.class));
    }

    @Test
    void scoreAndSelectBest_emptyCandidates_returnsEmpty() {
        Optional<String> result = scorer().scoreAndSelectBest("ORG", "hello", List.of());
        assertTrue(result.isEmpty());
        verify(vectorStore, never()).similaritySearch(any(SearchRequest.class));
    }

    @Test
    void scoreAndSelectBest_noResultsAboveThreshold_returnsEmpty() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        Optional<String> result = scorer().scoreAndSelectBest("ORG", "hello", List.of(agent("a")));

        assertTrue(result.isEmpty(),
                "When the vector store returns no docs above the cosine threshold, scorer must yield empty");
    }

    @Test
    void scoreAndSelectBest_topMatchInCandidateSet_returnsAgentId() {
        Document hit = new Document("agent description", Map.of("agentId", "agent-weather"));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(hit));

        Optional<String> result = scorer().scoreAndSelectBest(
                "ORG", "what's the weather?", List.of(agent("agent-weather"), agent("agent-billing")));

        assertEquals(Optional.of("agent-weather"), result);
        verify(embeddingService).embedMissing(eq("ORG"), any());
    }

    @Test
    void scoreAndSelectBest_topMatchNotInCandidateSet_returnsEmpty() {
        // Roster changed since the embedding was written — vector says "agent-X" but
        // it's no longer active. Scorer must reject so the resolver cascade continues.
        Document staleHit = new Document("description", Map.of("agentId", "agent-removed"));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(staleHit));

        Optional<String> result = scorer().scoreAndSelectBest(
                "ORG", "anything", List.of(agent("agent-still-here")));

        assertTrue(result.isEmpty(),
                "Stale embedding pointing to a removed agent must not be returned");
    }

    @Test
    void scoreAndSelectBest_vectorStoreThrows_softFailsToEmpty() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new RuntimeException("pgvector unreachable"));

        Optional<String> result = scorer().scoreAndSelectBest("ORG", "hello", List.of(agent("a")));

        assertTrue(result.isEmpty(),
                "Any exception from the vector path must be swallowed so the resolver cascade can continue");
    }
}
