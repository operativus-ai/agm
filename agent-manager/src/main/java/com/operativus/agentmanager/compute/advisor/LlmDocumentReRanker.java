package com.operativus.agentmanager.compute.advisor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Domain Responsibility: Re-ranks candidate documents using an LLM to score relevance via a single
 * bulk prompt. Submits all candidate chunks in ONE request and receives a structured JSON score array
 * back, avoiding the O(N) sequential-call anti-pattern. Refines the RRF-ordered candidate pool
 * ({@code AdvancedRagAdvisor}) down to topN by LLM-judged relevance; fail-open to the input order.
 *
 * State: Stateless (Ephemeral Strategy Object)
 *
 * @architecture NOT a Spring {@code @Component}. Instantiated ephemerally in
 *     {@code AgentClientFactory.buildChatClient()} from the agent's resolved {@code ChatModel} only
 *     when {@code agent.rag.reranker.enabled=true} (default OFF → {@link PassthroughDocumentReRanker}).
 *     Building from the resolved model keeps it in sync with DB-level model routing without restarts.
 *     Reintroduced in PR #1229 (scope: docs/analysis/rag-reranker-scope.md); the interface + wiring
 *     seam were kept when the original LLM reranker was trimmed in #1062.
 */
public class LlmDocumentReRanker implements DocumentReRanker {

    private static final Logger log = LoggerFactory.getLogger(LlmDocumentReRanker.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_CHUNK_CHARS = 800;

    private final ChatClient scoringClient;

    /**
     * Structured POJO for the LLM's per-document relevance scores. The LLM returns a JSON array
     * of these in a single network hop.
     */
    public static class DocumentScore {
        private int index;
        private double score;

        public DocumentScore() {}
        public DocumentScore(int index, double score) {
            this.index = index;
            this.score = score;
        }

        public int index() { return index; }
        public void setIndex(int index) { this.index = index; }
        public double score() { return score; }
        public void setScore(double score) { this.score = score; }
    }

    public LlmDocumentReRanker(ChatModel scoringModel) {
        this.scoringClient = ChatClient.builder(scoringModel).build();
    }

    /**
     * @summary Scores all documents in a single bulk LLM call and returns the top N.
     * @logic
     * 1. Constructs a single prompt containing all candidate chunks indexed by position.
     * 2. Requests a JSON array of {@link DocumentScore} objects back from the LLM.
     * 3. Parses the structured response, maps scores to originating documents, sorts descending, topN.
     * 4. Falls back to positional truncation if LLM scoring fails entirely (fail-open).
     */
    @Override
    public List<Document> rerank(String query, List<Document> documents, int topN) {
        if (documents == null || documents.isEmpty()) {
            return documents != null ? documents.subList(0, Math.min(topN, documents.size())) : List.of();
        }

        log.info("LLM Bulk Re-Ranking {} documents for query: '{}'", documents.size(),
                query.length() > 60 ? query.substring(0, 60) + "..." : query);

        try {
            List<DocumentScore> scores = executeBulkScoring(query, documents);
            return applyScores(documents, scores, topN);
        } catch (Exception e) {
            log.warn("LLM Bulk Re-Ranking failed. Returning first {} documents by input order.", topN, e);
            return documents.subList(0, Math.min(topN, documents.size()));
        }
    }

    /**
     * @summary Builds a single bulk prompt with all indexed chunks and invokes the LLM once.
     * @logic Concatenates all document texts with chunk indices. Instructs the LLM to return
     *        a JSON array of {@code {"index": N, "score": 0.0-1.0}} objects. Parses via Jackson.
     */
    private List<DocumentScore> executeBulkScoring(String query, List<Document> documents) throws Exception {
        StringBuilder chunksBlock = new StringBuilder();
        for (int i = 0; i < documents.size(); i++) {
            String text = documents.get(i).getText();
            if (text == null || text.isBlank()) {
                text = "(empty)";
            }
            String truncated = text.length() > MAX_CHUNK_CHARS
                    ? text.substring(0, MAX_CHUNK_CHARS) + "..."
                    : text;
            chunksBlock.append("CHUNK_").append(i).append(": \"\"\"").append(truncated).append("\"\"\"\n\n");
        }

        String bulkPrompt = """
            You are a document relevance scorer. Rate each chunk's relevance to the query.

            Query: "%s"

            %s

            Return ONLY a JSON array with one entry per chunk. Each entry must have:
            - "index": the chunk number (integer)
            - "score": relevance score from 0.0 (irrelevant) to 1.0 (highly relevant)

            Example output: [{"index": 0, "score": 0.85}, {"index": 1, "score": 0.2}]

            Return ONLY the raw JSON array. No markdown fences, no explanations.
            """.formatted(query, chunksBlock.toString());

        String response = scoringClient.prompt(bulkPrompt).call().content();

        // Strip markdown fences if present (LLMs love to add them).
        String cleaned = response.strip();
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            if (firstNewline > 0) cleaned = cleaned.substring(firstNewline + 1);
            if (cleaned.endsWith("```")) cleaned = cleaned.substring(0, cleaned.length() - 3);
            cleaned = cleaned.strip();
        }

        List<DocumentScore> scores = MAPPER.readValue(cleaned, new TypeReference<List<DocumentScore>>() {});
        log.debug("LLM Bulk Scoring returned {} score entries for {} documents.", scores.size(), documents.size());
        return scores;
    }

    /**
     * @summary Maps parsed LLM scores to the original document list, sorts descending, limits to topN.
     * @logic Validates each returned index is in bounds and clamps scores to [0,1]. Documents the LLM
     *        did not score receive a fallback of 0.0 so they sort to the bottom.
     */
    private List<Document> applyScores(List<Document> documents, List<DocumentScore> scores, int topN) {
        double[] scoreArray = new double[documents.size()];

        for (DocumentScore ds : scores) {
            if (ds.index() >= 0 && ds.index() < documents.size()) {
                scoreArray[ds.index()] = Math.max(0.0, Math.min(1.0, ds.score()));
            }
        }

        List<Document> result = IntStream.range(0, documents.size())
                .boxed()
                .sorted(Comparator.comparingDouble((Integer i) -> scoreArray[i]).reversed())
                .limit(topN)
                .map(documents::get)
                .collect(Collectors.toList());

        int maxIdx = IntStream.range(0, documents.size())
                .boxed()
                .max(Comparator.comparingDouble(i -> scoreArray[i]))
                .orElse(0);
        log.info("LLM Re-Ranking complete. Top {} documents selected (highest score: {}).",
                result.size(), result.isEmpty() ? "N/A" : String.format("%.2f", scoreArray[maxIdx]));
        return result;
    }
}
