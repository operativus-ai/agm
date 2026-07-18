package ai.operativus.agentmanager.integration.evaluation;

import ai.operativus.agentmanager.control.repository.EvaluationRunRepository;
import ai.operativus.agentmanager.control.repository.EvaluationSuiteRepository;
import ai.operativus.agentmanager.core.entity.EvaluationRun;
import ai.operativus.agentmanager.core.entity.EvaluationSuite;
import ai.operativus.agentmanager.core.model.enums.RunStatus;
import ai.operativus.agentmanager.integration.BaseIntegrationTest;
import ai.operativus.agentmanager.integration.support.FakeChatModelConfig;
import ai.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pins {@link EvaluationRunRepository#findAllInOrg} — the
 *   tenant-scoped fetch consumed by {@code EvaluationController.getEvaluationMetrics}.
 *   {@code EvaluationRun} has no direct {@code org_id} column; the boundary is enforced
 *   via subquery against {@code EvaluationSuite.orgId}.
 *
 *   <p>Without this test, a refactor that drops the subquery, broadens it (e.g. flips
 *   AND/OR), or compares against the wrong field would silently leak cross-tenant
 *   evaluation runs into a tenant's metrics aggregate — a different tenant's pass/fail
 *   counts would appear alongside the caller's. No error surface; just wrong dashboard
 *   numbers.
 *
 *   <p>Same shape as {@code KnowledgeContentRepository.findByKnowledgeBaseIdAndCallerOrgId}
 *   (PR #1035) and the third HIGH-priority cross-tenant @Query finding from the round-2
 *   repository audit.
 *
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class EvaluationRunRepositoryCrossTenantRuntimeTest extends BaseIntegrationTest {

    @Autowired
    private EvaluationRunRepository runRepo;
    @Autowired
    private EvaluationSuiteRepository suiteRepo;

    @BeforeEach
    void resetState() {
        truncateDatabase();
    }

    @Test
    void findAllInOrg_sameOrg_returnsOnlyThatOrgsRuns() {
        EvaluationSuite suiteA = seedSuite("org-A", "suite-A");
        String agentA1 = seedAgent();
        String agentA2 = seedAgent();
        runRepo.save(run(suiteA.getId(), agentA1, RunStatus.COMPLETED));
        runRepo.save(run(suiteA.getId(), agentA2, RunStatus.RUNNING));

        List<EvaluationRun> runs = runRepo.findAllInOrg("org-A");
        assertEquals(2, runs.size(), "org-A caller must see both runs in their suite");
    }

    @Test
    void findAllInOrg_crossOrgCaller_doesNotLeakRunsFromOtherOrgs() {
        // CRITICAL security pin: caller for org-A must NOT see org-B's runs even though
        // both orgs have evaluations in the same table. The subquery against
        // EvaluationSuite.orgId is the boundary; without it, every org would see every
        // other org's runs in their metrics aggregate.
        EvaluationSuite suiteA = seedSuite("org-A", "suite-A");
        EvaluationSuite suiteB = seedSuite("org-B", "suite-B");
        runRepo.save(run(suiteA.getId(), seedAgent(), RunStatus.COMPLETED));
        runRepo.save(run(suiteB.getId(), seedAgent(), RunStatus.COMPLETED));
        runRepo.save(run(suiteB.getId(), seedAgent(), RunStatus.FAILED));

        List<EvaluationRun> runsForA = runRepo.findAllInOrg("org-A");
        assertEquals(1, runsForA.size(),
                "org-A caller must see ONLY their 1 run, not org-B's 2 runs");
        assertEquals(suiteA.getId(), runsForA.get(0).getSuiteId(),
                "the only returned run must belong to suiteA");

        List<EvaluationRun> runsForB = runRepo.findAllInOrg("org-B");
        assertEquals(2, runsForB.size(),
                "org-B caller must see ONLY their 2 runs, not org-A's 1");
        assertTrue(runsForB.stream().allMatch(r -> suiteB.getId().equals(r.getSuiteId())),
                "all org-B's returned runs must belong to suiteB");
    }

    @Test
    void findAllInOrg_multipleSuitesPerOrg_aggregatesAllRunsAcrossSuites() {
        // Verify the IN clause genuinely UNIONs all this-org suites' runs — not just
        // the first suite found.
        EvaluationSuite suite1 = seedSuite("org-A", "suite-1");
        EvaluationSuite suite2 = seedSuite("org-A", "suite-2");
        runRepo.save(run(suite1.getId(), seedAgent(), RunStatus.COMPLETED));
        runRepo.save(run(suite1.getId(), seedAgent(), RunStatus.RUNNING));
        runRepo.save(run(suite2.getId(), seedAgent(), RunStatus.FAILED));

        List<EvaluationRun> runs = runRepo.findAllInOrg("org-A");
        assertEquals(3, runs.size(),
                "all 3 runs across the org's 2 suites must be returned (subquery IN-clause aggregates)");
    }

    @Test
    void findAllInOrg_unknownOrg_returnsEmptyList() {
        EvaluationSuite suiteA = seedSuite("org-A", "suite-A");
        runRepo.save(run(suiteA.getId(), seedAgent(), RunStatus.COMPLETED));

        List<EvaluationRun> runs = runRepo.findAllInOrg("org-nonexistent");
        assertTrue(runs.isEmpty(),
                "unknown orgId scopes subquery to empty suite candidate set → no runs match");
    }

    @Test
    void findAllInOrg_orgWithSuitesButNoRuns_returnsEmpty() {
        // Boundary: an org may have suites configured but no runs executed yet.
        // The query must return empty, not error.
        seedSuite("org-A", "suite-A");

        List<EvaluationRun> runs = runRepo.findAllInOrg("org-A");
        assertTrue(runs.isEmpty(), "org with suites but zero runs must return empty list");
    }

    @Test
    void findAllInOrg_threeOrgsThreeSuitesThreeRuns_eachCallerOnlySeesOwn() {
        // Multi-tenant matrix pin: 3 orgs × 1 suite × 1 run each. Each callerOrgId
        // must see exactly the 1 row that belongs to them, not the 2 belonging to others.
        EvaluationSuite suiteA = seedSuite("org-A", "suite-A");
        EvaluationSuite suiteB = seedSuite("org-B", "suite-B");
        EvaluationSuite suiteC = seedSuite("org-C", "suite-C");
        EvaluationRun runA = runRepo.save(run(suiteA.getId(), seedAgent(), RunStatus.COMPLETED));
        EvaluationRun runB = runRepo.save(run(suiteB.getId(), seedAgent(), RunStatus.COMPLETED));
        EvaluationRun runC = runRepo.save(run(suiteC.getId(), seedAgent(), RunStatus.COMPLETED));

        List<EvaluationRun> aRuns = runRepo.findAllInOrg("org-A");
        List<EvaluationRun> bRuns = runRepo.findAllInOrg("org-B");
        List<EvaluationRun> cRuns = runRepo.findAllInOrg("org-C");

        assertEquals(1, aRuns.size());
        assertEquals(runA.getId(), aRuns.get(0).getId());
        assertEquals(1, bRuns.size());
        assertEquals(runB.getId(), bRuns.get(0).getId());
        assertEquals(1, cRuns.size());
        assertEquals(runC.getId(), cRuns.get(0).getId());
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    /**
     * Seeds a minimal {@code agents} row so {@code evaluation_runs.agent_id} FK
     * ({@code evaluation_runs_agent_id_fkey}) is satisfied. Returns the new id so each
     * run can attach to its own dummy agent.
     */
    private String seedAgent() {
        String id = "test-agent-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO agents (id, name) VALUES (?, ?)", id, "test-agent-name");
        return id;
    }

    private EvaluationSuite seedSuite(String orgId, String namePrefix) {
        // EvaluationSuite uses String @Id (no @GeneratedValue); assign explicitly.
        // Names are @Column(nullable=false) but NOT unique on this table, so prefixed
        // UUIDs avoid collisions across tests.
        EvaluationSuite s = new EvaluationSuite();
        s.setId(UUID.randomUUID().toString());
        s.setName(namePrefix + "-" + UUID.randomUUID().toString().substring(0, 8));
        s.setOrgId(orgId);
        return suiteRepo.save(s);
    }

    private static EvaluationRun run(String suiteId, String agentId, RunStatus status) {
        EvaluationRun r = new EvaluationRun();
        r.setId(UUID.randomUUID().toString());
        r.setSuiteId(suiteId);
        r.setAgentId(agentId);
        r.setStatus(status);
        return r;
    }
}
