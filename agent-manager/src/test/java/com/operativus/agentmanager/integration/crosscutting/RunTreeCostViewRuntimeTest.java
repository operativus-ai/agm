package com.operativus.agentmanager.integration.crosscutting;

import com.operativus.agentmanager.control.repository.RunRepository;
import com.operativus.agentmanager.core.entity.AgentRun;
import com.operativus.agentmanager.core.model.enums.RunStatus;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Runtime coverage for {@code vw_run_tree_cost} (changeset 036,
 *   logging plan §5.22). Pins the recursive-CTE contract that the view sums {@code total_cost_usd}
 *   across the full transitive closure rooted at {@code parent_run_id IS NULL}, and that the
 *   two {@link RunRepository} native queries that surface the view behave correctly for both
 *   root-anchored lookups and the upward-walking variant.
 * State: Stateless. Seeds parent + child + grandchild runs per test; cleaned up by the
 *   shared {@code truncateDatabase} AfterEach in {@link BaseIntegrationTest}.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class RunTreeCostViewRuntimeTest extends BaseIntegrationTest {

    @Autowired private RunRepository runRepository;

    // Plan §5.22 — the view's whole point is aggregating a run tree's cost in one read.
    // Seeds root + 2 children + 1 grandchild (3-level tree). Asserts both findTreeCostByRootRunId
    // (root-anchored) and findTreeCostByAnyRunId (walks up from deepest node) return the same
    // rollup (1.50 + 2.25 + 0.75 + 0.50 = 5.00 USD, run_count = 4).
    @Test
    void vwRunTreeCostAggregatesParentPlusChildrenPlusGrandchildIntoSingleRollup() {
        String rootId = "root-" + UUID.randomUUID();
        String child1Id = "child1-" + UUID.randomUUID();
        String child2Id = "child2-" + UUID.randomUUID();
        String grandchildId = "gc-" + UUID.randomUUID();

        persistRun(rootId, null, new BigDecimal("1.50"));
        persistRun(child1Id, rootId, new BigDecimal("2.25"));
        persistRun(child2Id, rootId, new BigDecimal("0.75"));
        persistRun(grandchildId, child1Id, new BigDecimal("0.50"));

        List<Object[]> byRoot = runRepository.findTreeCostByRootRunId(rootId);
        assertFalse(byRoot.isEmpty(), "root-anchored lookup must return a row when the root exists");
        Object[] rootRow = byRoot.get(0);
        assertEquals(rootId, rootRow[0], "row[0] must echo the root_run_id");
        assertEquals(0, new BigDecimal("5.00").compareTo((BigDecimal) rootRow[1]),
                "row[1] must be the full tree sum 1.50 + 2.25 + 0.75 + 0.50 = 5.00");
        assertEquals(4L, ((Number) rootRow[2]).longValue(),
                "row[2] must count every node in the tree, including the root itself");

        List<Object[]> byGrandchild = runRepository.findTreeCostByAnyRunId(grandchildId);
        assertFalse(byGrandchild.isEmpty(), "upward-walking lookup must find the root from a 2-hop descendant");
        Object[] gcRow = byGrandchild.get(0);
        assertEquals(rootId, gcRow[0], "upward walk must resolve to the same root");
        assertEquals(0, new BigDecimal("5.00").compareTo((BigDecimal) gcRow[1]),
                "the rollup returned via upward walk must equal the root-anchored rollup");
        assertEquals(4L, ((Number) gcRow[2]).longValue(),
                "the count returned via upward walk must equal the root-anchored count");
    }

    // Empty-tree invariant: a root with no flushed cost and no children must still surface in
    // the view, reporting tree_total_cost_usd=0 (not NULL) and run_count=1. COALESCE(SUM, 0) in
    // the view body guards this — the assertion pins that behavior so the frontend doesn't NPE.
    @Test
    void vwRunTreeCostReportsZeroNotNullForRootWithNoFlushedCost() {
        String rootId = "root-empty-" + UUID.randomUUID();
        persistRun(rootId, null, null);

        List<Object[]> rows = runRepository.findTreeCostByRootRunId(rootId);
        assertFalse(rows.isEmpty(), "a solo root must still appear in vw_run_tree_cost");
        Object[] row = rows.get(0);
        assertEquals(rootId, row[0]);
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) row[1]),
                "a tree with no total_cost_usd on any row must report 0, not NULL (COALESCE contract)");
        assertEquals(1L, ((Number) row[2]).longValue(),
                "run_count must be 1 — the root itself");
    }

    // Contract: findTreeCostByRootRunId only matches rows where root_run_id is actually a root.
    // Calling it with a child run's id must return null, not silently fall through. Callers that
    // don't know the root must use findTreeCostByAnyRunId instead.
    @Test
    void findTreeCostByRootRunIdReturnsNullWhenCalledWithNonRootRunId() {
        String rootId = "root-" + UUID.randomUUID();
        String childId = "child-" + UUID.randomUUID();
        persistRun(rootId, null, new BigDecimal("1.00"));
        persistRun(childId, rootId, new BigDecimal("2.00"));

        List<Object[]> rows = runRepository.findTreeCostByRootRunId(childId);
        assertTrue(rows.isEmpty(),
                "findTreeCostByRootRunId must not match non-root ids — callers use findTreeCostByAnyRunId for that");
    }

    // --- seeding helpers ---------------------------------------------------------------

    private String persistRun(String id, String parentRunId, BigDecimal totalCostUsd) {
        seedFakeAgent();
        AgentRun r = new AgentRun();
        r.setId(id);
        r.setAgentId("agent-tree-cost");
        r.setStatus(RunStatus.COMPLETED);
        runRepository.saveAndFlush(r);
        // parent_run_id and total_cost_usd aren't exposed on the AgentRun entity as
        // settable fields in every version, and total_cost_usd is written by the accumulator
        // flush path in prod. A native UPDATE is the least-coupled way to shape rows for
        // the view assertion — mirrors the created_at override pattern used in
        // RetentionErasureRuntimeTest.persistRun.
        jdbc.update("UPDATE agent_runs SET parent_run_id = ?, total_cost_usd = ? WHERE id = ?",
                parentRunId, totalCostUsd, id);
        return id;
    }

    private void seedFakeAgent() {
        // agent_runs.agent_id FKs to agents(id); agents.model_id FKs to models(id).
        // Seed both parent rows idempotently so parallel test classes can own their setup.
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('fake-tree-cost-model', 'fake-tree-cost-model', 'fake', 'fake-tree-cost-model',
                        true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, created_at, updated_at)
                VALUES ('agent-tree-cost', 'tree cost agent', 'fake-tree-cost-model', true, now(), now())
                ON CONFLICT (id) DO NOTHING
                """);
    }
}
