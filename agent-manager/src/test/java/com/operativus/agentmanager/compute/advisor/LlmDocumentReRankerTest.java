package com.operativus.agentmanager.compute.advisor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LlmDocumentReRanker} (reintroduced in PR #1229). Verifies:
 * - Successful single-hop bulk JSON extraction and score-ordered document mapping.
 * - Markdown fence stripping ({@code ```json ... ```}).
 * - Out-of-bounds / omitted-index safety (hallucinated indices ignored; unscored docs sink to 0.0).
 * - Score clamping to [0,1].
 * - Fail-open to input order on unparseable LLM output.
 * The enabled/disabled gate lives at the AgentClientFactory wiring site, not in this class.
 */
class LlmDocumentReRankerTest {

    private ChatModel chatModel;
    private LlmDocumentReRanker reranker;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        org.springframework.ai.chat.prompt.ChatOptions options = mock(org.springframework.ai.chat.prompt.ChatOptions.class);
        org.springframework.ai.chat.prompt.ChatOptions.Builder optBuilder = mock(org.springframework.ai.chat.prompt.ChatOptions.Builder.class);
        when(optBuilder.build()).thenReturn(options);
        when(options.mutate()).thenReturn(optBuilder);
        when(chatModel.getDefaultOptions()).thenReturn(options);
        // Spring AI 2.0: DefaultChatClientUtils.toChatClientRequest reads ChatModel.getOptions();
        // without this stub the ChatClient build NPEs and silently falls back to input order.
        when(chatModel.getOptions()).thenReturn(options);
        reranker = new LlmDocumentReRanker(chatModel);
    }

    @Test
    @DisplayName("Empty or null document lists return gracefully")
    void emptyOrNull_returnsGracefully() {
        assertThat(reranker.rerank("Query", List.of(), 5)).isEmpty();
        assertThat(reranker.rerank("Query", null, 5)).isEmpty();
    }

    @Test
    @DisplayName("Maps LLM bulk JSON array to documents, ordered by score descending")
    void successfulBulkScoring_reordersByScore() {
        List<Document> documents = List.of(new Document("Doc 0"), new Document("Doc 1"), new Document("Doc 2"));
        // LLM ranks Doc 1 highest, Doc 2 lowest.
        mockLlmResponse("[{\"index\": 0, \"score\": 0.5}, {\"index\": 1, \"score\": 0.9}, {\"index\": 2, \"score\": 0.1}]");

        List<Document> result = reranker.rerank("Find me the best doc", documents, 3);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).getText()).isEqualTo("Doc 1"); // 0.9
        assertThat(result.get(1).getText()).isEqualTo("Doc 0"); // 0.5
        assertThat(result.get(2).getText()).isEqualTo("Doc 2"); // 0.1
    }

    @Test
    @DisplayName("Parses bulk JSON when wrapped in Markdown fences")
    void markdownFencesStripped() {
        List<Document> documents = List.of(new Document("Doc 0"), new Document("Doc 1"));
        mockLlmResponse("```json\n[{\"index\": 0, \"score\": 0.2}, {\"index\": 1, \"score\": 0.8}]\n```");

        List<Document> result = reranker.rerank("Query", documents, 2);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getText()).isEqualTo("Doc 1"); // 0.8
    }

    @Test
    @DisplayName("Safely ignores out-of-bounds (hallucinated) indices")
    void outOfBoundsIndices_ignored() {
        List<Document> documents = List.of(new Document("Doc 0"), new Document("Doc 1"));
        mockLlmResponse("[{\"index\": 0, \"score\": 0.2}, {\"index\": 1, \"score\": 0.8}, {\"index\": 99, \"score\": 1.0}]");

        List<Document> result = reranker.rerank("Query", documents, 2);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getText()).isEqualTo("Doc 1");
        assertThat(result.get(1).getText()).isEqualTo("Doc 0");
    }

    @Test
    @DisplayName("Falls back to input-order truncation on unparseable LLM output")
    void unparseableOutput_fallsBackToInputOrder() {
        List<Document> documents = List.of(new Document("Doc 0"), new Document("Doc 1"), new Document("Doc 2"));
        mockLlmResponse("I apologize, but I cannot fulfill this request.");

        List<Document> result = reranker.rerank("Query", documents, 2);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getText()).isEqualTo("Doc 0");
        assertThat(result.get(1).getText()).isEqualTo("Doc 1");
    }

    @Test
    @DisplayName("Documents the LLM forgets to score default to bottom rank")
    void omittedDocuments_defaultToZero() {
        List<Document> documents = List.of(
                new Document("Doc 0", Map.of("id", "0")),
                new Document("Doc 1", Map.of("id", "1")),
                new Document("Doc 2", Map.of("id", "2")));
        // LLM forgets to score index 1.
        mockLlmResponse("[{\"index\": 0, \"score\": 0.5}, {\"index\": 2, \"score\": 0.9}]");

        List<Document> result = reranker.rerank("Query", documents, 3);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).getText()).isEqualTo("Doc 2"); // 0.9
        assertThat(result.get(1).getText()).isEqualTo("Doc 0"); // 0.5
        assertThat(result.get(2).getText()).isEqualTo("Doc 1"); // 0.0 fallback
    }

    @Test
    @DisplayName("Clamps scores outside [0.0, 1.0]")
    void scoreClamping() {
        List<Document> documents = List.of(new Document("Doc 0"), new Document("Doc 1"));
        mockLlmResponse("[{\"index\": 0, \"score\": -5.0}, {\"index\": 1, \"score\": 2.5}]");

        List<Document> result = reranker.rerank("Query", documents, 2);

        // Doc 1 clamped to 1.0 (top), Doc 0 clamped to 0.0 (bottom).
        assertThat(result.get(0).getText()).isEqualTo("Doc 1");
        assertThat(result.get(1).getText()).isEqualTo("Doc 0");
    }

    private void mockLlmResponse(String content) {
        var assistantMessage = new org.springframework.ai.chat.messages.AssistantMessage(content);
        var generation = new org.springframework.ai.chat.model.Generation(assistantMessage);
        ChatResponse chatResponse = new ChatResponse(List.of(generation));
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);
    }
}
