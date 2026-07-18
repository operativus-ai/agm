package com.operativus.agentmanager.compute.routing;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Domain Responsibility: Provides a {@link VectorStore} bean dedicated to agent description
 *     embeddings used by {@link SemanticAgentScorer}. Separate table from the RAG cache
 *     (changeset 091 leaves table creation to PgVectorStore.initializeSchema=true so the
 *     schema is owned by Spring AI, not Liquibase — mirrors the cacheVectorStore pattern
 *     in {@link com.operativus.agentmanager.config.VectorStoreConfig}).
 *
 *     <p>The auto-configured {@link EmbeddingModel} bean is the source of embeddings. Per
 *     DR-FR-3 cross-cutting answer B, this is a fixed system-wide model (default
 *     text-embedding-3-small via the org's OpenAI ProviderCredential).
 * State: Stateless (Spring config)
 */
@Configuration
public class RoutingVectorStoreConfig {

    @Bean(name = "routingVectorStore")
    public VectorStore routingVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .vectorTableName("routing_vectors")
                .initializeSchema(true)
                .build();
    }
}
