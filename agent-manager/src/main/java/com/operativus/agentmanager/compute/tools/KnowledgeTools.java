package com.operativus.agentmanager.compute.tools;

import com.operativus.agentmanager.control.security.RequiresCapability;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.filter.Filter.Expression;
import com.operativus.agentmanager.core.callback.AgentContextHolder;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Domain Responsibility: Provides a basic generic Spring AI tool for searching the configured VectorStore knowledge base.
 * State: Stateless
 */
@Service
public class KnowledgeTools {

    private final VectorStore vectorStore;

    public KnowledgeTools(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * @summary Searches the vector store knowledge base for a specified query.
     * @logic Queries the injected VectorStore for the top 5 relevant documents and concatenates their text values.
     */
    @RequiresCapability("memory_access")
    @Tool(description = "Useful for looking up specific financial data, reports, or documentation in the knowledge base.")
    public String searchKnowledgeBase(String query) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();

        // Base Organization Isolation
        String orgId = AgentContextHolder.getOrgId();
        var orgFilter = b.eq("orgId", orgId != null ? orgId : "DEFAULT_SYSTEM_ORG");

        // Row-Level Access Control (RLAC) Bounds
        List<String> allowedKbs = AgentContextHolder.getAllowedKnowledgeBaseIds();
        Expression filter;
        if (allowedKbs != null && !allowedKbs.isEmpty()) {
            filter = b.and(orgFilter, b.in("knowledge_base_id", allowedKbs.toArray(new Object[0]))).build();
        } else {
            filter = orgFilter.build();
        }

        // Search for top 5 relevant documents bounded natively in Postgres via pgvector constraints
        List<Document> documents = vectorStore.similaritySearch(
            SearchRequest.builder()
                .query(query)
                .topK(5)
                .filterExpression(filter)
                .build()
        );

        // Combine content
        return documents.stream()
            .map(Document::getText)
            .collect(Collectors.joining("\n\n"));
    }
}
