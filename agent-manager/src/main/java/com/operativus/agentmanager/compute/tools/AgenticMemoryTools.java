package com.operativus.agentmanager.compute.tools;

import com.operativus.agentmanager.control.security.AgentSecurityFilters;
import com.operativus.agentmanager.control.security.RequiresCapability;
import com.operativus.agentmanager.core.callback.AgentContextHolder;
import com.operativus.agentmanager.core.model.MetadataKeys;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter.Expression;
import org.springframework.ai.document.Document;


import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Domain Responsibility: Provides Spring AI tools for querying and updating RAG-based semantic memory (Vector Store).
 * State: Stateless
 */
@AgentToolComponent
public class AgenticMemoryTools {

    private final VectorStore vectorStore;

    public AgenticMemoryTools(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * @summary Searches the knowledge base for relevant documents, org- and KB-scoped.
     * @logic Applies {@link AgentSecurityFilters#buildVectorFilter()} (orgId + storeType=KB +
     *        the agent's KB allowlist; fail-closed when orgId is unbound) so this tool cannot
     *        pull another tenant's rows out of the shared vector_store. The filter is null only
     *        for ROLE_SUPER_ADMIN (unbounded), matching KnowledgeService.search.
     */
    @RequiresCapability("memory_access")
    @Tool(description = "Search the internal knowledge base for documents, policies, or facts. Use this when the user asks a question requiring retrieval.")
    public String search_knowledge_base(@ToolParam(description = "The search query") String query) {
        Expression filter = AgentSecurityFilters.buildVectorFilter();
        SearchRequest.Builder request = SearchRequest.builder().query(query).topK(5);
        if (filter != null) {
            request.filterExpression(filter);
        }

        List<Document> docs = vectorStore.similaritySearch(request.build());

        if (docs.isEmpty()) {
            return "No relevant information found in knowledge base.";
        }

        return docs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n---\n"));
    }

    /**
     * @summary Saves a user fact or preference to the caller's tenant-scoped long-term memory.
     * @logic Tags the vector row with the active orgId and storeType=MEMORY (mirroring
     *        MemoryService.addMemory) so memory-scoped, org-scoped retrieval can find it and no
     *        other tenant can. Refuses to write when no orgId is resolvable — an untagged row
     *        would be globally visible to every tenant, which is the leak this closes.
     */
    @RequiresCapability("memory_access")
    @Tool(description = "Save a user fact or preference to long-term memory.")
    public String save_memory(@ToolParam(description = "The text content to remember") String content) {
        String orgId = AgentContextHolder.getOrgId();
        if (orgId == null || orgId.isBlank()) {
            return "Unable to save memory: no active organization context.";
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put(MetadataKeys.ORG_ID, orgId);
        metadata.put(MetadataKeys.STORE_TYPE, MetadataKeys.STORE_TYPE_MEMORY);

        Document doc = new Document(UUID.randomUUID().toString(), content, metadata);
        vectorStore.add(List.of(doc));
        return "Memory saved.";
    }
}
