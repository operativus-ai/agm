package ai.operativus.agentmanager.integration.agents;

import ai.operativus.agentmanager.control.repository.AgentRepository;
import ai.operativus.agentmanager.core.entity.AgentEntity;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins {@link AgentRepository#countAgentsGroupedByModelId} —
 *   GROUP BY aggregate used by {@code ModelService.loadAgentCounts} to surface
 *   per-model agent usage in the Models admin page.
 *
 *   <p>The query: {@code SELECT a.modelId, COUNT(a) FROM AgentEntity a
 *   WHERE a.modelId IS NOT NULL GROUP BY a.modelId}. Returns {@code List<Object[]>}
 *   where each row is {@code [modelId (String), count (Long)]}. The caller's cast
 *   {@code (Long) row[1]} relies on Hibernate's COUNT-aggregate type — if a future
 *   driver/dialect change emits Integer/BigInteger instead, the ModelService cast
 *   would ClassCastException at runtime.
 *
 *   <p>Three subtle constraints:
 *   <ol>
 *     <li><b>NULL filter</b>: agents with null modelId are EXCLUDED (not bucketed
 *         into a "(null)" group). Without the {@code WHERE a.modelId IS NOT NULL}
 *         clause, a (null, N) row would appear and the caller's
 *         {@code counts.put((String) row[0], ...)} would put a null key in the map
 *         — survives in {@code HashMap} but downstream lookups by modelId would
 *         miss it silently.</li>
 *     <li><b>Aggregate row type</b>: row[1] must be Long (the caller casts to Long).</li>
 *     <li><b>One row per distinct modelId</b>: even if 100 agents share modelId X,
 *         only one row is returned with count=100.</li>
 *   </ol>
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class AgentRepositoryCountGroupedByModelIdRuntimeTest extends BaseIntegrationTest {

    @Autowired
    private AgentRepository agentRepo;

    @BeforeEach
    void resetState() {
        truncateDatabase();
    }

    @Test
    void countAgentsGroupedByModelId_singleModelMultipleAgents_returnsOneRowWithCount() {
        String modelX = "gpt-4o";
        agentRepo.save(agent(modelX));
        agentRepo.save(agent(modelX));
        agentRepo.save(agent(modelX));

        List<Object[]> rows = agentRepo.countAgentsGroupedByModelId();

        assertEquals(1, rows.size(), "must return exactly one row per distinct modelId");
        assertEquals(modelX, rows.get(0)[0], "row[0] is the modelId String");
        assertEquals(3L, rows.get(0)[1], "row[1] is the aggregate count as Long");
    }

    @Test
    void countAgentsGroupedByModelId_multipleModels_returnsRowPerDistinctModel() {
        agentRepo.save(agent("gpt-4o"));
        agentRepo.save(agent("gpt-4o"));
        agentRepo.save(agent("gpt-4o"));
        agentRepo.save(agent("claude-3-opus"));
        agentRepo.save(agent("claude-3-opus"));
        agentRepo.save(agent("gemini-2.5-pro"));

        Map<String, Long> counts = toCountMap(agentRepo.countAgentsGroupedByModelId());

        assertEquals(3, counts.size(), "3 distinct modelIds → 3 rows");
        assertEquals(3L, counts.get("gpt-4o"));
        assertEquals(2L, counts.get("claude-3-opus"));
        assertEquals(1L, counts.get("gemini-2.5-pro"));
    }

    @Test
    void countAgentsGroupedByModelId_agentsWithNullModelId_areExcluded() {
        // CRITICAL: WHERE a.modelId IS NOT NULL must filter out null-modelId agents
        // so the caller's (String) row[0] cast never encounters a null key.
        agentRepo.save(agent("gpt-4o"));
        agentRepo.save(agent(null));
        agentRepo.save(agent(null));
        agentRepo.save(agent(null));

        List<Object[]> rows = agentRepo.countAgentsGroupedByModelId();

        assertEquals(1, rows.size(),
                "null-modelId agents must NOT produce a (null, N) row — they're excluded by WHERE");
        assertEquals("gpt-4o", rows.get(0)[0]);
        assertEquals(1L, rows.get(0)[1],
                "the gpt-4o count must NOT inflate by the null-modelId agents");
    }

    @Test
    void countAgentsGroupedByModelId_allAgentsHaveNullModelId_returnsEmpty() {
        agentRepo.save(agent(null));
        agentRepo.save(agent(null));

        List<Object[]> rows = agentRepo.countAgentsGroupedByModelId();
        assertTrue(rows.isEmpty(),
                "if every agent has null modelId, the WHERE filters them all → empty result");
    }

    @Test
    void countAgentsGroupedByModelId_noAgentsAtAll_returnsEmpty() {
        List<Object[]> rows = agentRepo.countAgentsGroupedByModelId();
        assertTrue(rows.isEmpty());
    }

    @Test
    void countAgentsGroupedByModelId_aggregateRowTypeIsLong() {
        // The caller casts row[1] to Long. Hibernate's COUNT() returns Long for
        // JPQL aggregates, but a future dialect/driver change could emit Integer
        // or BigInteger. Pin the type so a regression surfaces here, not as a
        // ClassCastException at runtime inside ModelService.loadAgentCounts.
        agentRepo.save(agent("gpt-4o"));

        Object countCell = agentRepo.countAgentsGroupedByModelId().get(0)[1];
        assertTrue(countCell instanceof Long,
                "COUNT aggregate must return Long for the caller's (Long) cast to be safe; "
                        + "got " + countCell.getClass().getName());
    }

    @Test
    void countAgentsGroupedByModelId_countsAgentsAcrossAllOrgs() {
        // Defensive pin: the query has no org filter — it's a system-wide count for
        // the global Models admin page. Confirm by seeding agents in 2 orgs and
        // expecting both to roll into the same modelId bucket.
        AgentEntity a1 = agent("gpt-4o");
        a1.setOrgId("org-A");
        AgentEntity a2 = agent("gpt-4o");
        a2.setOrgId("org-B");
        agentRepo.save(a1);
        agentRepo.save(a2);

        Map<String, Long> counts = toCountMap(agentRepo.countAgentsGroupedByModelId());
        assertEquals(2L, counts.get("gpt-4o"),
                "system-wide aggregate must roll up agents across all orgs into one bucket");
    }

    @Test
    void countAgentsGroupedByModelId_inactiveAgentsAreCountedToo() {
        // Defensive pin: no `active = true` filter in the query. An inactive agent
        // still counts toward modelId usage in the admin page (it COULD be reactivated).
        AgentEntity active = agent("gpt-4o");
        AgentEntity inactive = agent("gpt-4o");
        inactive.setActive(false);
        agentRepo.save(active);
        agentRepo.save(inactive);

        Map<String, Long> counts = toCountMap(agentRepo.countAgentsGroupedByModelId());
        assertEquals(2L, counts.get("gpt-4o"),
                "inactive agents are NOT excluded — they still represent existing usage");
    }

    @Test
    void countAgentsGroupedByModelId_resultRowShape_isExactly2Elements() {
        // The caller accesses row[0] and row[1]. A future query refactor that adds
        // a third column (or drops one) would silently break the caller. Pin the
        // exact shape.
        agentRepo.save(agent("gpt-4o"));

        Object[] row = agentRepo.countAgentsGroupedByModelId().get(0);
        assertEquals(2, row.length, "result tuple must be exactly [modelId, count]");
    }

    @Test
    void countAgentsGroupedByModelId_mixedScenario_endToEnd() {
        // Integration scenario combining multiple constraints:
        //   - 4 agents with modelId "X" (1 inactive)
        //   - 1 agent with modelId "Y"
        //   - 2 agents with null modelId (excluded)
        //   - agents in 2 orgs (rolled up)
        // Expected: {"X": 4, "Y": 1}
        AgentEntity x1 = agent("X"); x1.setOrgId("org-A");
        AgentEntity x2 = agent("X"); x2.setOrgId("org-A");
        AgentEntity x3 = agent("X"); x3.setOrgId("org-B");
        AgentEntity x4 = agent("X"); x4.setOrgId("org-B"); x4.setActive(false);
        AgentEntity y1 = agent("Y"); y1.setOrgId("org-A");
        agentRepo.save(x1); agentRepo.save(x2); agentRepo.save(x3); agentRepo.save(x4); agentRepo.save(y1);
        agentRepo.save(agent(null));
        agentRepo.save(agent(null));

        Map<String, Long> counts = toCountMap(agentRepo.countAgentsGroupedByModelId());
        assertEquals(2, counts.size(), "only 2 distinct non-null modelIds");
        assertEquals(4L, counts.get("X"));
        assertEquals(1L, counts.get("Y"));
        assertFalse(counts.containsKey(null), "null-modelId bucket must not appear");
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    /** Build a minimal AgentEntity. AgentEntity uses String @Id (no @GeneratedValue).
     *  AUTO-SEEDS the parent models row for the given modelId so the FK constraint
     *  fk_agents_model_id is satisfied (null modelId bypasses the seed, as expected). */
    private AgentEntity agent(String modelId) {
        if (modelId != null) {
            seedModelIfMissing(modelId);
        }
        AgentEntity a = new AgentEntity();
        a.setId("test-agent-" + UUID.randomUUID().toString().substring(0, 8));
        a.setName("test-agent-name");
        a.setModelId(modelId);
        a.setActive(true);
        return a;
    }

    /** Inserts a minimal models row if one doesn't exist with this id. Reuses across
     *  tests that pass the same modelId (e.g. multiple agents all using "gpt-4o"). */
    private void seedModelIfMissing(String modelId) {
        Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM models WHERE id = ?", Integer.class, modelId);
        if (existing != null && existing > 0) return;
        jdbc.update(
                "INSERT INTO models (id, name, provider) VALUES (?, ?, ?)",
                modelId, "test-model-name", "OPENAI");
    }

    private static Map<String, Long> toCountMap(List<Object[]> rows) {
        return rows.stream().collect(Collectors.toMap(
                row -> (String) row[0],
                row -> (Long) row[1]));
    }
}
