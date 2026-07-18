package com.operativus.agentmanager.integration.routing;

import com.operativus.agentmanager.compute.routing.AgentEmbeddingService;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Real-pgvector regression coverage for the routing-embeddings write
 *   path. The unit tests ({@code AgentEmbeddingServiceTest} / {@code SemanticAgentScorerTest})
 *   mock the {@code VectorStore}, so they never exercise PgVectorStore's
 *   {@code UUID.fromString(doc.id())} call on insert — which is exactly why the
 *   {@code "routing:<org>:<agent>"} non-UUID document id (a 100%-broken write that failed with
 *   "UUID string too large") shipped undetected and 400'd the backfill endpoint live. This test
 *   writes through the real {@code routingVectorStore} against Testcontainers Postgres so any
 *   non-UUID document id regresses loudly here instead of only in production.
 * State: Stateless.
 */
@Import(FakeEmbeddingModelConfig.class)
class RoutingEmbeddingsBackfillRuntimeTest extends BaseIntegrationTest {

    private static final String MODEL_ID = "routing-fake-model";

    @Autowired private AgentEmbeddingService embeddingService;

    private void seedModelIfMissing() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES (?, ?, 'fake', ?, true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """, MODEL_ID, MODEL_ID, MODEL_ID);
    }

    private String seedAgent(String orgId, String description) {
        seedModelIfMissing();
        String id = "agent-routing-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agents (id, name, description, model_id,
                                    is_reasoning_enabled, is_team, requires_pii_redaction,
                                    approved_for_production, maintenance_mode, active,
                                    enforce_json_output, instructions, org_id, created_at, updated_at)
                VALUES (?, ?, ?, ?,
                        false, false, false, false, false, true, false,
                        'Be helpful.', ?, now(), now())
                """, id, "Routing " + id, description, MODEL_ID, orgId);
        return id;
    }

    @Test
    void embedAll_writesValidUuidRowsToRoutingVectors_andIsIdempotent() {
        String orgId = "org-routing-" + UUID.randomUUID();
        String a1 = seedAgent(orgId, "Handles billing disputes and customer refunds.");
        String a2 = seedAgent(orgId, "Schedules meetings and manages team calendars.");

        // Was 400 "UUID string too large" before the fix — this call goes through
        // PgVectorStore.add -> UUID.fromString(doc.id()) against real pgvector.
        AgentEmbeddingService.BackfillSummary summary = embeddingService.embedAll(orgId);
        assertEquals(2, summary.totalAgents());
        assertEquals(2, summary.embedded());

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, metadata->>'orgId' AS org, metadata->>'agentId' AS agent " +
                        "FROM routing_vectors WHERE metadata->>'orgId' = ?", orgId);
        assertEquals(2, rows.size(), "two routing vectors written for the org");
        for (Map<String, Object> row : rows) {
            // Exactly the call PgVectorStore makes on insert — must not throw (#1108 regression).
            UUID parsed = UUID.fromString(row.get("id").toString());
            assertEquals(row.get("id").toString(), parsed.toString());
            assertEquals(orgId, row.get("org"));
            assertTrue(List.of(a1, a2).contains(row.get("agent")));
        }

        // Deterministic document id -> re-running overwrites the same rows, never duplicates.
        AgentEmbeddingService.BackfillSummary rerun = embeddingService.embedAll(orgId);
        assertEquals(2, rerun.embedded());
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM routing_vectors WHERE metadata->>'orgId' = ?",
                Integer.class, orgId);
        assertEquals(2, count, "idempotent: re-run overwrites the same two rows, no duplicates");
    }
}
