package com.operativus.agentmanager.integration.evaluations;

import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Pin {@code EvaluationController} cross-tenant isolation.
 *   G2 (changeset 071) added {@code org_id} to {@code evaluation_suites} and wired
 *   {@code findAllByOrgId} / {@code findByIdAndOrgId} / {@code existsByIdAndOrgId}
 *   gates into the controller. Pre-G2 every read returned rows across all orgs
 *   and every mutation acted on any id regardless of ownership.
 *
 *   Contracts pinned here:
 *   <ul>
 *     <li>{@code GET /suites} — only own-org suites appear in the listing.</li>
 *     <li>{@code GET /suites/{B-id}} as A — 404 (existence-leak protection).</li>
 *     <li>{@code DELETE /suites/{B-id}} as A — 404 + B's row preserved.</li>
 *     <li>{@code GET /suites/{B-id}/cases} as A — 404 (no child enumeration).</li>
 *     <li>{@code POST /suites/{B-id}/cases} as A — 404 + no case row inserted in B's suite.</li>
 *     <li>{@code GET /suites/{B-id}/runs} as A — 404.</li>
 *     <li>{@code POST /suites/{B-id}/run} as A — 404 + no background_jobs row enqueued.</li>
 *     <li>{@code DELETE /cases/{B-case-id}} as A — 404 + case row preserved.</li>
 *     <li>{@code GET /runs/{B-run-id}/results} as A — 404.</li>
 *     <li>{@code GET /metrics} — aggregate filtered to caller-org runs only.</li>
 *   </ul>
 *
 * State: Stateless. Per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class,
        FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class EvaluationControllerTenantIsolationRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> JSON_LIST =
            new ParameterizedTypeReference<>() {};

    @BeforeEach
    void resetBeforeTest() {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    @Test
    void listSuites_returnsOnlyCallerOrgSuites() {
        HttpHeaders orgA = registerLoginWithOrg("eval-iso-a-list", "eval-iso-org-A");
        HttpHeaders orgB = registerLoginWithOrg("eval-iso-b-list", "eval-iso-org-B");
        seedSuite("A's Smoke", "eval-iso-org-A");
        seedSuite("A's Daily", "eval-iso-org-A");
        seedSuite("B's Smoke", "eval-iso-org-B");

        // G3 — /suites now returns Page<EvaluationSuite>; extract `.content`.
        ResponseEntity<Map<String, Object>> aResp = rest.exchange(
                url("/api/v1/evaluations/suites"), HttpMethod.GET,
                new HttpEntity<>(orgA), JSON_MAP);
        ResponseEntity<Map<String, Object>> bResp = rest.exchange(
                url("/api/v1/evaluations/suites"), HttpMethod.GET,
                new HttpEntity<>(orgB), JSON_MAP);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> aContent = (List<Map<String, Object>>) aResp.getBody().get("content");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bContent = (List<Map<String, Object>>) bResp.getBody().get("content");

        assertAll("listing scoped per-org",
                () -> assertEquals(HttpStatus.OK, aResp.getStatusCode()),
                () -> assertEquals(2, aContent.size(),
                        "org A must see exactly its 2 suites; got " + aContent),
                () -> assertTrue(aContent.stream().allMatch(s ->
                                String.valueOf(s.get("name")).startsWith("A's ")),
                        "every row in A's listing must be A-named; got " + aContent),
                () -> assertEquals(1, bContent.size(),
                        "org B must see exactly its 1 suite"));
    }

    // G3 — pagination contract for GET /suites. Seed 25 suites in caller org; assert
    // page 0/size 10 returns 10 rows + totalElements=25, page 2/size 10 returns the
    // final 5, and wire shape carries the nested `page` envelope (direct mode).
    @Test
    void listSuites_paginationContract() {
        HttpHeaders orgA = registerLoginWithOrg("eval-iso-a-page", "eval-iso-org-A");
        for (int i = 0; i < 25; i++) {
            seedSuite(String.format("Page Suite %02d", i), "eval-iso-org-A");
        }

        ResponseEntity<Map<String, Object>> page0 = rest.exchange(
                url("/api/v1/evaluations/suites?page=0&size=10"), HttpMethod.GET,
                new HttpEntity<>(orgA), JSON_MAP);
        ResponseEntity<Map<String, Object>> page2 = rest.exchange(
                url("/api/v1/evaluations/suites?page=2&size=10"), HttpMethod.GET,
                new HttpEntity<>(orgA), JSON_MAP);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> c0 = (List<Map<String, Object>>) page0.getBody().get("content");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> c2 = (List<Map<String, Object>>) page2.getBody().get("content");
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) page0.getBody().get("page");

        assertAll("pagination + nested page envelope",
                () -> assertEquals(10, c0.size(), "size=10 must cap page 0 to 10 rows"),
                () -> assertEquals(25L, ((Number) meta.get("totalElements")).longValue(),
                        "totalElements must reflect the full 25-row count"),
                () -> assertEquals(3L, ((Number) meta.get("totalPages")).longValue(),
                        "25 rows / size 10 → 3 pages"),
                () -> assertEquals(5, c2.size(),
                        "page 2 (final) must carry the remaining 5 rows"));
    }

    @Test
    void getSuite_crossTenant_returns404() {
        HttpHeaders orgA = registerLoginWithOrg("eval-iso-a-get", "eval-iso-org-A");
        registerLoginWithOrg("eval-iso-b-get", "eval-iso-org-B");
        String bSuiteId = seedSuite("B's Probe", "eval-iso-org-B");

        ResponseEntity<String> resp = rest.exchange(
                url("/api/v1/evaluations/suites/" + bSuiteId), HttpMethod.GET,
                new HttpEntity<>(orgA), String.class);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "GET /suites/{B-id} as A must return 404 (existence-leak protection)");
    }

    @Test
    void deleteSuite_crossTenant_returns404_andRowSurvives() {
        HttpHeaders orgA = registerLoginWithOrg("eval-iso-a-del", "eval-iso-org-A");
        registerLoginWithOrg("eval-iso-b-del", "eval-iso-org-B");
        String bSuiteId = seedSuite("B's Delete-Probe", "eval-iso-org-B");

        ResponseEntity<String> resp = rest.exchange(
                url("/api/v1/evaluations/suites/" + bSuiteId), HttpMethod.DELETE,
                new HttpEntity<>(orgA), String.class);

        assertAll("cross-tenant DELETE is a no-op + 404",
                () -> assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode()),
                () -> {
                    Long alive = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM evaluation_suites WHERE id = ?", Long.class, bSuiteId);
                    assertEquals(1L, alive == null ? 0L : alive,
                            "cross-tenant DELETE must not have removed B's suite row");
                });
    }

    @Test
    void getCasesForSuite_crossTenant_returns404() {
        HttpHeaders orgA = registerLoginWithOrg("eval-iso-a-cases", "eval-iso-org-A");
        registerLoginWithOrg("eval-iso-b-cases", "eval-iso-org-B");
        String bSuiteId = seedSuite("B's Case-Probe", "eval-iso-org-B");
        seedCase(bSuiteId, "B's secret case");

        ResponseEntity<String> resp = rest.exchange(
                url("/api/v1/evaluations/suites/" + bSuiteId + "/cases"), HttpMethod.GET,
                new HttpEntity<>(orgA), String.class);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "GET /suites/{B-id}/cases as A must be 404 — no child enumeration via a known suiteId");
    }

    @Test
    void addCaseToSuite_crossTenant_returns404_andNoCaseInserted() {
        HttpHeaders orgA = registerLoginWithOrg("eval-iso-a-addcase", "eval-iso-org-A");
        registerLoginWithOrg("eval-iso-b-addcase", "eval-iso-org-B");
        String bSuiteId = seedSuite("B's Add-Probe", "eval-iso-org-B");

        Map<String, Object> body = Map.of("name", "smuggled", "input", "hello");
        ResponseEntity<String> resp = rest.exchange(
                url("/api/v1/evaluations/suites/" + bSuiteId + "/cases"), HttpMethod.POST,
                new HttpEntity<>(body, orgA), String.class);

        assertAll("cross-tenant addCase blocked",
                () -> assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode()),
                () -> {
                    Long count = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM evaluation_cases WHERE suite_id = ?",
                            Long.class, bSuiteId);
                    assertEquals(0L, count == null ? 0L : count,
                            "rejected cross-tenant POST must not insert a case row into B's suite");
                });
    }

    @Test
    void getRunsForSuite_crossTenant_returns404() {
        HttpHeaders orgA = registerLoginWithOrg("eval-iso-a-runs", "eval-iso-org-A");
        registerLoginWithOrg("eval-iso-b-runs", "eval-iso-org-B");
        String bSuiteId = seedSuite("B's Runs-Probe", "eval-iso-org-B");

        ResponseEntity<String> resp = rest.exchange(
                url("/api/v1/evaluations/suites/" + bSuiteId + "/runs"), HttpMethod.GET,
                new HttpEntity<>(orgA), String.class);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "GET /suites/{B-id}/runs as A must be 404 — historical run enumeration blocked");
    }

    @Test
    void runSuite_crossTenant_returns404_andNoJobEnqueued() {
        HttpHeaders orgA = registerLoginWithOrg("eval-iso-a-run", "eval-iso-org-A");
        registerLoginWithOrg("eval-iso-b-run", "eval-iso-org-B");
        String bSuiteId = seedSuite("B's Run-Probe", "eval-iso-org-B");
        String agentId = seedAgentInOrg("eval-iso-run-agent", "eval-iso-org-A");

        ResponseEntity<String> resp = rest.exchange(
                url("/api/v1/evaluations/suites/" + bSuiteId + "/run?agentId=" + agentId),
                HttpMethod.POST, new HttpEntity<>(orgA), String.class);

        assertAll("cross-tenant runSuite blocked at the controller",
                () -> assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode()),
                () -> {
                    Long jobs = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM background_jobs WHERE agent_id = ?",
                            Long.class, agentId);
                    assertEquals(0L, jobs == null ? 0L : jobs,
                            "rejected cross-tenant trigger must not enqueue an EVALUATION_RUN job");
                });
    }

    @Test
    void deleteCase_crossTenant_returns404_andRowSurvives() {
        HttpHeaders orgA = registerLoginWithOrg("eval-iso-a-delcase", "eval-iso-org-A");
        registerLoginWithOrg("eval-iso-b-delcase", "eval-iso-org-B");
        String bSuiteId = seedSuite("B's Delete-Case-Probe", "eval-iso-org-B");
        String bCaseId = seedCase(bSuiteId, "B's only case");

        ResponseEntity<String> resp = rest.exchange(
                url("/api/v1/evaluations/cases/" + bCaseId), HttpMethod.DELETE,
                new HttpEntity<>(orgA), String.class);

        assertAll("cross-tenant deleteCase blocked",
                () -> assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode()),
                () -> {
                    Long alive = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM evaluation_cases WHERE id = ?", Long.class, bCaseId);
                    assertEquals(1L, alive == null ? 0L : alive,
                            "cross-tenant DELETE must not have removed B's case row");
                });
    }

    @Test
    void getRunResults_crossTenant_returns404() {
        HttpHeaders orgA = registerLoginWithOrg("eval-iso-a-results", "eval-iso-org-A");
        registerLoginWithOrg("eval-iso-b-results", "eval-iso-org-B");
        String bSuiteId = seedSuite("B's Results-Probe", "eval-iso-org-B");
        String bAgentId = seedAgentInOrg("eval-iso-results-agent", "eval-iso-org-B");
        String bRunId = "run-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO evaluation_runs (id, suite_id, agent_id, status, started_at, completed_at)
                VALUES (?, ?, ?, 'COMPLETED', now(), now())
                """, bRunId, bSuiteId, bAgentId);

        ResponseEntity<String> resp = rest.exchange(
                url("/api/v1/evaluations/runs/" + bRunId + "/results"), HttpMethod.GET,
                new HttpEntity<>(orgA), String.class);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "GET /runs/{B-run-id}/results as A must be 404 — no per-case score data leak");
    }

    @Test
    void metrics_aggregateScopedToCallerOrg() {
        HttpHeaders orgA = registerLoginWithOrg("eval-iso-a-metrics", "eval-iso-org-A");
        registerLoginWithOrg("eval-iso-b-metrics", "eval-iso-org-B");
        String aSuite = seedSuite("A's metric-suite", "eval-iso-org-A");
        String bSuite = seedSuite("B's metric-suite", "eval-iso-org-B");
        seedAgentInOrg("metric-agent", "eval-iso-org-A");
        seedRunWithPassedTotals(aSuite, 10, 10);
        seedRunWithPassedTotals(bSuite, 7, 7);

        ResponseEntity<Map<String, Object>> aResp = rest.exchange(
                url("/api/v1/evaluations/metrics"), HttpMethod.GET,
                new HttpEntity<>(orgA), JSON_MAP);

        assertAll("metrics scoped to caller-org runs only",
                () -> assertEquals(HttpStatus.OK, aResp.getStatusCode()),
                () -> assertEquals(1, ((Number) aResp.getBody().get("totalRuns")).intValue(),
                        "A must only see its 1 run; B's run must be excluded"),
                () -> assertEquals(10, ((Number) aResp.getBody().get("totalCases")).intValue(),
                        "totalCases must reflect only A's runs"),
                () -> assertEquals(10, ((Number) aResp.getBody().get("passedCases")).intValue(),
                        "passedCases must reflect only A's runs"));
    }

    // ─── helpers ───

    private String seedSuite(String name, String orgId) {
        String id = "suite-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO evaluation_suites (id, name, description, created_by, org_id, created_at, updated_at)
                VALUES (?, ?, 'tenant-iso fixture', 'system', ?, now(), now())
                """, id, name, orgId);
        return id;
    }

    private String seedCase(String suiteId, String name) {
        String id = "case-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO evaluation_cases (id, suite_id, name, input, created_at, updated_at)
                VALUES (?, ?, ?, 'probe', now(), now())
                """, id, suiteId, name);
        return id;
    }

    private String seedAgentInOrg(String label, String orgId) {
        String id = "agent-" + label + "-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, active, org_id, created_at, updated_at)
                VALUES (?, ?, 'gpt-4o-mini', true, ?, now(), now())
                """, id, "EvalIso Agent " + label, orgId);
        return id;
    }

    private void seedRunWithPassedTotals(String suiteId, int totalCases, int passedCases) {
        String runId = "run-" + UUID.randomUUID();
        // suite-resident agentId — we don't care which org it's in for the metrics aggregate
        // (the aggregate joins through suite.org_id, not run.agent_id).
        String agentId = seedAgentInOrg("metric-fixture", "DEFAULT_SYSTEM_ORG");
        jdbc.update("""
                INSERT INTO evaluation_runs (id, suite_id, agent_id, status, started_at, completed_at,
                                             total_cases, passed_cases, failed_cases)
                VALUES (?, ?, ?, 'COMPLETED', now(), now(), ?, ?, ?)
                """, runId, suiteId, agentId, totalCases, passedCases, totalCases - passedCases);
    }
}
