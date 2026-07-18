package ai.operativus.agentmanager.compute.advisor;

import ai.operativus.agentmanager.control.service.KnowledgeService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins {@link AdvancedRagAdvisor} prompt-augmentation behavior.
 *
 * <p>The advisor sits at order 0 (pre-PII boundary, so injected chunks are
 * subject to redaction at order 10 — see {@code AdvisorPiiBoundaryContractTest}
 * for the documented embedding-API leak tradeoff). On each call it runs
 * concurrent semantic + keyword search via {@link KnowledgeService}, merges
 * the candidate pools, hands them to a {@link DocumentReRanker}, and rewrites
 * the prompt with a unified system message containing the KB context plus a
 * fallback instruction.
 *
 * <p>Cases pin:
 * <ol>
 *   <li>Blank user query → chain receives the original request untouched.</li>
 *   <li>Happy path: KB documents become a single augmented SystemMessage.</li>
 *   <li>Multiple SystemMessages in the input are unified to ONE (Google GenAI compat).</li>
 *   <li>Reranker exception falls back to RRF and still injects context.</li>
 *   <li>Candidate pool dedups by Document id across semantic + keyword sets.</li>
 *   <li>User message text is preserved verbatim through augmentation.</li>
 *   <li>Order==0 + name pinned (this slot is asserted in the boundary contract).</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class AdvancedRagAdvisorTest {

    @Mock private KnowledgeService knowledgeService;
    @Mock private DocumentReRanker documentReRanker;
    @Mock private CallAdvisorChain chain;

    private AdvancedRagAdvisor advisor;

    @BeforeEach
    void setUp() {
        SearchRequest searchRequest = SearchRequest.builder().query("probe").topK(5).build();
        advisor = new AdvancedRagAdvisor(
                knowledgeService, searchRequest, documentReRanker, new SimpleMeterRegistry());
    }

    @Test
    void blankUserQuery_passesRequestThroughUntouched() {
        ChatClientRequest in = requestWith(List.of(new SystemMessage("you are helpful")));
        when(chain.nextCall(in)).thenReturn(emptyResponse());

        advisor.adviseCall(in, chain);

        verify(chain).nextCall(in);
        verify(knowledgeService, never()).search(anyString(), anyInt());
        verify(knowledgeService, never()).keywordSearch(anyString(), anyInt());
    }

    @Test
    void happyPath_augmentsSystemMessage_withKbContextAndFallbackInstruction() {
        ChatClientRequest in = requestWith(List.of(
                new SystemMessage("base system"),
                new UserMessage("explain RAG")));
        Document d1 = doc("d1", "RAG combines retrieval with generation.", "https://example.com/rag");
        when(knowledgeService.search("explain RAG", 20)).thenReturn(List.of(d1));
        when(knowledgeService.keywordSearch("explain RAG", 20)).thenReturn(List.of());
        when(documentReRanker.rerank("explain RAG", List.of(d1), 5)).thenReturn(List.of(d1));
        when(chain.nextCall(any())).thenReturn(emptyResponse());

        advisor.adviseCall(in, chain);

        Prompt augmented = captureAugmentedPrompt();
        List<Message> messages = augmented.getInstructions();
        long systemCount = messages.stream().filter(m -> m.getMessageType() == MessageType.SYSTEM).count();
        assertThat(systemCount).as("must collapse to exactly one SystemMessage").isEqualTo(1L);

        String system = messages.get(0).getText();
        assertThat(system).contains("base system");
        assertThat(system).contains("KNOWLEDGE BASE CONTEXT");
        assertThat(system).contains("RAG combines retrieval with generation.");
        assertThat(system).contains("https://example.com/rag");
        assertThat(system).contains("IMPORTANT INSTRUCTION");
    }

    @Test
    void multipleSystemMessages_areUnifiedIntoOne() {
        ChatClientRequest in = requestWith(List.of(
                new SystemMessage("persona-A"),
                new SystemMessage("persona-B"),
                new UserMessage("hi")));
        when(knowledgeService.search("hi", 20)).thenReturn(List.of());
        when(knowledgeService.keywordSearch("hi", 20)).thenReturn(List.of());
        when(documentReRanker.rerank(anyString(), any(), anyInt())).thenReturn(List.of());
        when(chain.nextCall(any())).thenReturn(emptyResponse());

        advisor.adviseCall(in, chain);

        Prompt augmented = captureAugmentedPrompt();
        long systemCount = augmented.getInstructions().stream()
                .filter(m -> m.getMessageType() == MessageType.SYSTEM).count();
        assertThat(systemCount).isEqualTo(1L);
        String system = augmented.getInstructions().get(0).getText();
        assertThat(system).contains("persona-A").contains("persona-B");
    }

    @Test
    void rerankerThrows_fallsBackToRrf_andStillInjectsContext() {
        ChatClientRequest in = requestWith(List.of(new UserMessage("explain RAG")));
        Document semantic1 = doc("s1", "semantic content one", "src-s1");
        Document keyword1 = doc("k1", "keyword content one", "src-k1");
        when(knowledgeService.search("explain RAG", 20)).thenReturn(List.of(semantic1));
        when(knowledgeService.keywordSearch("explain RAG", 20)).thenReturn(List.of(keyword1));
        when(documentReRanker.rerank(anyString(), any(), anyInt()))
                .thenThrow(new RuntimeException("reranker down"));
        when(chain.nextCall(any())).thenReturn(emptyResponse());

        advisor.adviseCall(in, chain);

        Prompt augmented = captureAugmentedPrompt();
        String system = augmented.getInstructions().get(0).getText();
        // RRF fallback must inject BOTH the semantic and keyword candidate texts.
        assertThat(system).contains("semantic content one");
        assertThat(system).contains("keyword content one");
        assertThat(system).contains("KNOWLEDGE BASE CONTEXT");
    }

    @Test
    void candidatePool_dedupsByDocumentId_acrossSemanticAndKeyword() {
        ChatClientRequest in = requestWith(List.of(new UserMessage("q")));
        // Same id "shared" appears in both result sets — pool must contain only ONE entry.
        Document shared = doc("shared", "shared text", "src");
        Document onlySemantic = doc("s-only", "semantic only", "src");
        Document onlyKeyword = doc("k-only", "keyword only", "src");

        when(knowledgeService.search("q", 20)).thenReturn(List.of(shared, onlySemantic));
        when(knowledgeService.keywordSearch("q", 20)).thenReturn(List.of(shared, onlyKeyword));

        ArgumentCaptor<List<Document>> poolCaptor = ArgumentCaptor.forClass(List.class);
        when(documentReRanker.rerank(anyString(), poolCaptor.capture(), anyInt())).thenReturn(List.of());
        when(chain.nextCall(any())).thenReturn(emptyResponse());

        advisor.adviseCall(in, chain);

        List<Document> pool = poolCaptor.getValue();
        assertThat(pool).extracting(Document::getId)
                .as("shared doc must appear exactly once across the merged pool")
                .containsExactlyInAnyOrder("shared", "s-only", "k-only");
    }

    @Test
    void userMessageText_isPreservedVerbatimThroughAugmentation() {
        ChatClientRequest in = requestWith(List.of(new UserMessage("Please cite your sources.")));
        when(knowledgeService.search(anyString(), anyInt())).thenReturn(List.of());
        when(knowledgeService.keywordSearch(anyString(), anyInt())).thenReturn(List.of());
        when(documentReRanker.rerank(anyString(), any(), anyInt())).thenReturn(List.of());
        when(chain.nextCall(any())).thenReturn(emptyResponse());

        advisor.adviseCall(in, chain);

        Prompt augmented = captureAugmentedPrompt();
        Message user = augmented.getInstructions().stream()
                .filter(m -> m.getMessageType() == MessageType.USER)
                .findFirst().orElseThrow();
        assertThat(user.getText()).isEqualTo("Please cite your sources.");
    }

    @Test
    void orderingAndName_arePinned() {
        // Order 0 is the pre-PII slot — the boundary test owns the relationship
        // assertion; this is the local invariant pin.
        assertThat(advisor.getOrder()).isEqualTo(0);
        assertThat(advisor.getName()).isEqualTo("AdvancedRagAdvisor");
    }

    private Prompt captureAugmentedPrompt() {
        ArgumentCaptor<ChatClientRequest> captor = ArgumentCaptor.forClass(ChatClientRequest.class);
        verify(chain).nextCall(captor.capture());
        return captor.getValue().prompt();
    }

    private static ChatClientRequest requestWith(List<Message> messages) {
        return ChatClientRequest.builder()
                .prompt(new Prompt(messages))
                .context(new HashMap<>())
                .build();
    }

    private static ChatClientResponse emptyResponse() {
        return ChatClientResponse.builder().build();
    }

    private static Document doc(String id, String text, String sourceUrl) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("source_url", sourceUrl);
        return new Document(id, text, meta);
    }
}
