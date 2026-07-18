package com.operativus.agentmanager.compute.memory;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Domain Responsibility: Handles native pgvector semantic graph (Subject-Predicate-Object) extractions.
 * Uses JdbcClient to bypass Hibernate `@Entity` mappings, allowing us to enforce
 * immutable java `record` bounds across the memory layer natively.
 */
@Repository
public class PgVectorGraphRepository {

    private final JdbcClient jdbcClient;

    public PgVectorGraphRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public record SemanticTuple(
            UUID id,
            String subject,
            String predicate,
            String objectContext,
            String documentId,
            String userId
            // Assuming pgvector embeddings are handled at the vectorStore level
            // but the tuple relationships are stored here.
    ) {}

    /**
     * Persists an extracted semantic graph node.
     */
    public void saveTuple(SemanticTuple tuple) {
        String sql = """
            INSERT INTO semantic_graph_tuples (id, subject, predicate, object_context, document_id, user_id)
            VALUES (:id, :subject, :predicate, :objectContext, :documentId, :userId)
            ON CONFLICT (id) DO NOTHING
            """;
            
        jdbcClient.sql(sql)
                .param("id", tuple.id())
                .param("subject", tuple.subject())
                .param("predicate", tuple.predicate())
                .param("objectContext", tuple.objectContext())
                .param("documentId", tuple.documentId())
                .param("userId", tuple.userId())
                .update();
    }

    /**
     * Retrieves tuples for a specific user to form a Knowledge Graph projection.
     */
    public List<SemanticTuple> getUserGraph(String userId) {
        String sql = """
            SELECT id, subject, predicate, object_context, document_id, user_id 
            FROM semantic_graph_tuples 
            WHERE user_id = :userId
            """;
            
        return jdbcClient.sql(sql)
                .param("userId", userId)
                .query(SemanticTuple.class)
                .list();
    }
}
