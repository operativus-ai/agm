package com.operativus.agentmanager.core.registry;

/**
 * Domain Responsibility: Registry contract for ingesting and categorizing knowledge base materials.
 * State: Stateless
 */
public interface KnowledgeOperations {

    /**
     * @summary Ingests raw text content into the specified knowledge base.
     * @logic Stores text, metadata, and performs vector embeddings for the given sources.
     */
    void ingestText(String title, String content, String sourceUrl, java.util.UUID knowledgeBaseId);

    /**
     * @summary Resolves or creates a category identifier based on a name or fallback URL.
     * @logic Looks up existing category by name; if absent, derives or creates one from the fallback URL.
     */
    java.util.UUID resolveCategoryId(String categoryName, String fallbackUrl);
}
