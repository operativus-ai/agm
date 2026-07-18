package ai.operativus.agentmanager.compute.tools;

import ai.operativus.agentmanager.core.callback.AgentContextHolder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Domain Responsibility: Runtime test for {@link KnowledgeTools#searchKnowledgeBase} —
 * the actual implementation backing {@code ResearchTools.searchKnowledgeBaseTool}
 * (the @Tool surface exposed to LLMs). ResearchTools delegates 1:1 to this class, so
 * pinning behavior here pins the LLM-facing contract. Asserts the 5 vectors:
 *   (a) success path: 3 documents from VectorStore → joined with "\n\n" separator
 *   (b) null orgId (no ScopedValue bound) → filter falls back to "DEFAULT_SYSTEM_ORG"
 *   (c) RLAC: non-empty allowedKnowledgeBaseIds list → filter uses AND of orgId +
 *       knowledge_base_id IN list (security-critical: prevents cross-KB leak)
 *   (d) RLAC: null/empty allowedKnowledgeBaseIds → filter has only org isolation
 *   (e) topK is fixed at 5 across all calls
 *
 * State: Stateless. Mockito stub of VectorStore + ArgumentCaptor on SearchRequest
 * provide independent ground truth (A18) — the captured request reflects what
 * KnowledgeTools actually sent, not a mirror of the source.
 */
class KnowledgeToolsTest {

    private final VectorStore vectorStore = mock(VectorStore.class);
    private final KnowledgeTools tool = new KnowledgeTools(vectorStore);

    private static final String DEFAULT_ORG_FALLBACK = "DEFAULT_SYSTEM_ORG";

    private SearchRequest capturedRequest() {
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());
        return captor.getValue();
    }

    /** Run the tool inside ScopedValue bindings for orgId + allowedKnowledgeBaseIds. */
    private String runWith(String orgId, List<String> allowedKbs, String query) {
        // We need to chain bindings. Easiest: nested .where().run().
        Holder<String> result = new Holder<>();
        Runnable inner = () -> result.value = tool.searchKnowledgeBase(query);
        Runnable middle = () -> {
            if (allowedKbs != null) {
                ScopedValue.where(AgentContextHolder.allowedKnowledgeBaseIds, allowedKbs).run(inner);
            } else {
                inner.run();
            }
        };
        if (orgId != null) {
            ScopedValue.where(AgentContextHolder.orgId, orgId).run(middle);
        } else {
            middle.run();
        }
        return result.value;
    }

    private static final class Holder<T> { T value; }

    // (a) success path — 3 docs joined with "\n\n"
    @Test
    void searchKnowledgeBase_threeDocuments_joinedWithDoubleNewline() {
        when(vectorStore.similaritySearch(org.mockito.ArgumentMatchers.any(SearchRequest.class)))
                .thenReturn(List.of(new Document("alpha"), new Document("beta"), new Document("gamma")));

        String result = runWith("org-123", null, "policy");

        assertEquals("alpha\n\nbeta\n\ngamma", result);
    }

    // (b) null orgId → filter uses DEFAULT_SYSTEM_ORG fallback
    @Test
    void searchKnowledgeBase_nullOrgId_usesDefaultSystemOrgInFilter() {
        when(vectorStore.similaritySearch(org.mockito.ArgumentMatchers.any(SearchRequest.class)))
                .thenReturn(List.of());

        runWith(null, null, "anything");

        SearchRequest req = capturedRequest();
        assertNotNull(req.getFilterExpression(), "filter expression must always be applied");
        String filterRendered = req.getFilterExpression().toString();
        assertTrue(filterRendered.contains(DEFAULT_ORG_FALLBACK),
                "expected DEFAULT_SYSTEM_ORG fallback when no orgId is bound, got: " + filterRendered);
        assertTrue(filterRendered.contains("orgId"),
                "expected orgId filter, got: " + filterRendered);
    }

    // (c) non-empty allowedKbs → filter is AND(orgId == X, knowledge_base_id IN [...])
    @Test
    void searchKnowledgeBase_allowedKbsNonEmpty_filterAndsKnowledgeBaseInList() {
        when(vectorStore.similaritySearch(org.mockito.ArgumentMatchers.any(SearchRequest.class)))
                .thenReturn(List.of());

        runWith("org-7", List.of("kb-public", "kb-finance"), "Q3 report");

        String filterRendered = capturedRequest().getFilterExpression().toString();
        assertTrue(filterRendered.contains("org-7"), "expected concrete orgId, got: " + filterRendered);
        assertTrue(filterRendered.contains("knowledge_base_id"),
                "expected knowledge_base_id constraint, got: " + filterRendered);
        assertTrue(filterRendered.contains("kb-public"),
                "expected first KB id in filter, got: " + filterRendered);
        assertTrue(filterRendered.contains("kb-finance"),
                "expected second KB id in filter, got: " + filterRendered);
    }

    // (d) null/empty allowedKbs → filter ONLY has org isolation, no knowledge_base_id clause
    @Test
    void searchKnowledgeBase_emptyAllowedKbs_filterIsOrgOnly() {
        when(vectorStore.similaritySearch(org.mockito.ArgumentMatchers.any(SearchRequest.class)))
                .thenReturn(List.of());

        runWith("org-9", List.of(), "anything");

        String filterRendered = capturedRequest().getFilterExpression().toString();
        assertTrue(filterRendered.contains("org-9"), "expected orgId, got: " + filterRendered);
        assertTrue(!filterRendered.contains("knowledge_base_id"),
                "empty allowedKbs MUST NOT add knowledge_base_id constraint, got: " + filterRendered);
    }

    // (e) topK is fixed at 5
    @Test
    void searchKnowledgeBase_topKIsFixedAtFive() {
        when(vectorStore.similaritySearch(org.mockito.ArgumentMatchers.any(SearchRequest.class)))
                .thenReturn(List.of());

        runWith("org-1", null, "any query");

        assertEquals(5, capturedRequest().getTopK(), "topK must be 5");
    }
}
