package com.operativus.agentmanager.compute.tools;

import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.model.MetadataKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Domain Responsibility: Runtime test for {@link AgenticMemoryTools}. Sister test to
 * ActiveMemoryToolsTest — covers the AGM-specific RAG-over-VectorStore overlay.
 *
 * <p>Pins the tenant-isolation contract added to close a cross-tenant leak: pre-fix both tools
 * hit the shared vector_store with NO filter (read) and NO metadata (write), so any tenant's
 * agent could read every org's rows and writes were globally visible.
 *   (a) search_knowledge_base applies {@link com.operativus.agentmanager.control.security.AgentSecurityFilters}
 *       (non-null filterExpression) and concatenates document text with "\n---\n"
 *   (b) search_knowledge_base empty results → canonical "No relevant information found in knowledge base."
 *   (c) search_knowledge_base with NO orgId bound → fail-closed (IllegalStateException), never queries
 *   (d) save_memory tags orgId + storeType=MEMORY and returns "Memory saved."
 *   (e) save_memory with NO orgId bound → refuses, does NOT write an untagged (globally-visible) row
 *
 * State: Stateless. Mockito stub of VectorStore is independent ground truth (A18).
 */
class AgenticMemoryToolsTest {

    private static final String ORG = "org-A";

    private final VectorStore vectorStore = mock(VectorStore.class);
    private final AgenticMemoryTools tool = new AgenticMemoryTools(vectorStore);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // (a) search happy path — tenant-scoped filter applied, 3 documents concatenated
    @Test
    void searchKnowledgeBase_threeDocs_appliesFilterAndConcatenates() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                new Document("doc one body"),
                new Document("doc two body"),
                new Document("doc three body")));

        String result = ScopedValue.where(AgentContextHolder.orgId, ORG)
                .call(() -> tool.search_knowledge_base("policy"));

        assertEquals("doc one body\n---\ndoc two body\n---\ndoc three body", result);

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());
        assertNotNull(captor.getValue().getFilterExpression(),
                "search must apply a tenant-scoping filter, not query the shared store unbounded");
    }

    // (b) search empty results -> canonical fallback
    @Test
    void searchKnowledgeBase_emptyResults_returnsCanonicalFallback() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        String result = ScopedValue.where(AgentContextHolder.orgId, ORG)
                .call(() -> tool.search_knowledge_base("obscure topic"));

        assertEquals("No relevant information found in knowledge base.", result);
    }

    // (c) search with NO orgId -> fail-closed, never touches the vector store
    @Test
    void searchKnowledgeBase_noOrgId_failsClosed() {
        assertThrows(IllegalStateException.class, () -> tool.search_knowledge_base("policy"));
        verify(vectorStore, never()).similaritySearch(any(SearchRequest.class));
    }

    // (d) save_memory -> tags orgId + storeType=MEMORY, returns canonical confirmation
    @Test
    void saveMemory_happyPath_tagsTenantMetadataAndPersists() {
        String result = ScopedValue.where(AgentContextHolder.orgId, ORG)
                .call(() -> tool.save_memory("user prefers concise answers"));

        assertEquals("Memory saved.", result);

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        Document written = captor.getValue().get(0);
        assertEquals("user prefers concise answers", written.getText());
        assertEquals(ORG, written.getMetadata().get(MetadataKeys.ORG_ID));
        assertEquals(MetadataKeys.STORE_TYPE_MEMORY, written.getMetadata().get(MetadataKeys.STORE_TYPE));
    }

    // (e) save_memory with NO orgId -> refuses; never writes an untagged, globally-visible row
    @Test
    void saveMemory_noOrgId_refusesWrite() {
        String result = tool.save_memory("user prefers concise answers");

        assertEquals("Unable to save memory: no active organization context.", result);
        verify(vectorStore, never()).add(any());
    }
}
