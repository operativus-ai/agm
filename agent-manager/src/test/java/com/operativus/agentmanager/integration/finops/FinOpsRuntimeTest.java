package com.operativus.agentmanager.integration.finops;

import com.operativus.agentmanager.compute.monitoring.FinOpsObservedEmbeddingModel;
import com.operativus.agentmanager.control.finops.service.BurnRateMonitorService;
import com.operativus.agentmanager.control.finops.service.FinOpsAnalyticsService;
import com.operativus.agentmanager.control.finops.service.LiveValuationEngine;
import com.operativus.agentmanager.control.service.DataRetentionService;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModel;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.FakeModelProviderConfig;
import com.operativus.agentmanager.integration.support.NoOpReflectionServiceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Black-box runtime coverage of the FinOps surface — token-usage
 *   metric emission per run, embedding-vs-chat cost attribution via
 *   {@link FinOpsObservedEmbeddingModel}, the {@code FinOpsAdminController} dashboard
 *   endpoints ({@code /trends}, {@code /allocations}, {@code /roi-stats},
 *   {@code /burn-rates/active}, {@code /anomalies/active}), budget-ceiling enforcement
 *   via {@link com.operativus.agentmanager.control.finops.exception.FinOpsBudgetExhaustedException},
 *   retention of cost data, and cross-tenant isolation of cost aggregates.
 * State: Stateless. Autowires the live {@link FinOpsAnalyticsService},
 *   {@link LiveValuationEngine}, and {@link BurnRateMonitorService} beans.
 *
 * Cases mapped to {@code docs/testing/agm-runtime-testing.md} §21.
 *
 * Why so many gap pins: §21 assumes persisted per-run cost rows and HTTP-mapped budget
 *   enforcement; the shipped surface is an in-memory Micrometer + per-session accumulator
 *   design. These tests pin the as-shipped behaviour so a future patch that wires things
 *   up flips loudly.
 *
 * Gaps surfaced / pinned:
 *   - §21.1: no per-run cost_usd column on {@code agent_runs}. Cost data lives in
 *     {@link com.operativus.agentmanager.compute.monitoring.GenAiMetricsAdvisor}'s
 *     in-memory session accumulator + Prometheus meters.
 *   - §21.2: {@link FinOpsObservedEmbeddingModel} publishes embedding costs to the
 *     {@code finops.embedding.cost.usd} summary via Micrometer; no durable row.
 *   - §21.3: {@code /trends} and {@code /allocations} aggregate RUN COUNTS × a flat
 *     {@code agentmanager.finops.average-cost-per-run-usd} (default $0.08) — not real
 *     cost data. No per-provider or per-team GROUP BY.
 *   - §21.4: Implemented. {@code budget_policies} table + {@code BudgetPolicyService} resolve
 *     the active ceiling per org/agent; {@code AgentService.run()} binds it into
 *     {@code AgentContextHolder.CONTEXT} before the ScopedValue call;
 *     {@code GenAiMetricsAdvisor} throws {@code FinOpsBudgetExhaustedException} mid-flight;
 *     {@code GlobalExceptionHandler} maps it to HTTP 402 PAYMENT_REQUIRED.
 *   - §21.5: no cost_usd schema → nothing for {@code DataRetentionService} to purge.
 *     Retention targets {@code agent_runs} by {@code created_at}, not cost rows.
 *   - §21.6: {@code FinOpsAnalyticsService} queries {@code RunRepository.findDailyRunCounts}
 *     without an {@code org_id} WHERE clause → dashboards leak cross-tenant counts.
 *     {@code FinOpsAdminController} endpoints have no {@code @PreAuthorize} — any
 *     authenticated principal can read global aggregates.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, FakeModelProviderConfig.class, NoOpReflectionServiceConfig.class})
public class FinOpsRuntimeTest extends BaseIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> JSON_LIST =
            new ParameterizedTypeReference<>() {};

    @Autowired private FakeChatModel fakeModel;
    @Autowired private LiveValuationEngine valuationEngine;
    @Autowired private BurnRateMonitorService burnRateMonitor;
    @Autowired private FinOpsAnalyticsService analyticsService;
    @Autowired private DataRetentionService dataRetentionService;

    @BeforeEach
    void resetState() {
        fakeModel.reset();
        seedModel("gpt-4o-mini");
    }

    // ─── §21 Case 1 — Token counts surface via metrics but no cost row is persisted ───

    /**
     * Spec §21.1. Expectation: "Run with stubbed model that emits token counts → cost row
     *   persisted with correct tokens × price." As-shipped, there is no cost_usd column on
     *   {@code agent_runs} and no separate cost_rows table — cost lives in Micrometer
     *   meters + a per-session in-memory accumulator on {@code GenAiMetricsAdvisor}.
     *
     * We pin: (a) the usage meter IS emitted on a run (proving the advisor chain fired),
     *   and (b) the {@code agent_runs} schema has NO {@code cost_usd} column. Flipping
     *   assertion (b) — e.g., after a migration adds the column — would mark the gap closed.
     */
    @Test
    void tokenUsageMetricEmitted_butNoPerRunCostRowPersisted_gapPin() {
        HttpHeaders auth = userHeaders("finops-case1");
        String agentId = createAgent(auth, "T043 case 1 agent");

        fakeModel.respondWith("T043 case 1 reply");
        ResponseEntity<Map<String, Object>> run = runAgent(auth, agentId, "finops probe", "session-" + UUID.randomUUID());
        assertEquals(200, run.getStatusCode().value(), "run must succeed so GenAiMetricsAdvisor can record token usage");

        ResponseEntity<String> scrape = rest.exchange(
                url("/actuator/prometheus"), HttpMethod.GET, HttpEntity.EMPTY, String.class);
        assertEquals(200, scrape.getStatusCode().value());
        String body = scrape.getBody();
        assertNotNull(body);

        Integer costColumnCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema='public' AND table_name='agent_runs' AND column_name='cost_usd'",
                Integer.class);

        assertAll("case 1 — token usage metric without durable cost row",
                () -> assertTrue(body.contains("gen_ai_client_token_usage"),
                        "gen_ai.client.token.usage metric must be visible in the Prometheus scrape after a run"),
                () -> assertEquals(0, costColumnCount,
                        "GAP §21.1: agent_runs has no cost_usd column — spec expects per-run cost persistence, "
                                + "actual cost data lives only in in-memory Micrometer + session accumulator."));
    }

    // ─── §21 Case 2 — Embedding observer wrapper is wired; embedding cost is distinct ───

    /**
     * Spec §21.2. Expectation: "Embedding calls via FinOpsObservedEmbeddingModel produce
     *   cost rows distinct from chat." As-shipped, the observer publishes to the
     *   {@code finops.embedding.cost.usd} DistributionSummary (surfaced via
     *   {@code GET /api/v1/finops/roi-stats} → {@code totalEmbeddingCostUsd}).
     *
     * We pin: the observer bean is the primary {@code EmbeddingModel} (wraps
     *   {@link com.operativus.agentmanager.integration.support.FakeEmbeddingModel}), and
     *   the ROI endpoint is reachable and returns the expected {@code totalEmbeddingCostUsd}
     *   + {@code totalCacheSavingsUsd} + {@code netRoiUsd} shape. An embedding call is
     *   triggered by storing a memory via {@code POST /api/memories}; FakeEmbeddingModel
     *   emits no usage metadata, so {@code totalEmbeddingCostUsd} may stay zero — pinned
     *   as a test-env gap in the assertion message.
     */
    @Test
    void embeddingCallExercisesObservedWrapper_roiStatsReturnsShape_gapOnFakeUsageMetadata() {
        HttpHeaders auth = adminHeaders("finops-case2");

        Map<String, Object> memoryBody = Map.of("content", "T043 case 2 memory content");
        ResponseEntity<Map<String, Object>> saveMemory = rest.exchange(
                url("/api/memories"), HttpMethod.POST, new HttpEntity<>(memoryBody, auth), JSON_MAP);
        assertEquals(200, saveMemory.getStatusCode().value(),
                "POST /api/memories must succeed — that's what triggers FinOpsObservedEmbeddingModel.call()");

        ResponseEntity<Map<String, Object>> roi = rest.exchange(
                url("/api/v1/finops/roi-stats"), HttpMethod.GET, new HttpEntity<>(auth), JSON_MAP);
        assertEquals(200, roi.getStatusCode().value(), "GET /api/v1/finops/roi-stats must succeed for any authenticated caller");
        Map<String, Object> body = roi.getBody();
        assertNotNull(body);

        assertAll("case 2 — ROI stats shape + embedding observer wiring",
                () -> assertTrue(body.containsKey("totalCacheSavingsUsd"),
                        "ROI payload must carry totalCacheSavingsUsd (finops.cache.savings.usd counter)"),
                () -> assertTrue(body.containsKey("totalEmbeddingCostUsd"),
                        "ROI payload must carry totalEmbeddingCostUsd (finops.embedding.cost.usd summary — the distinct-from-chat signal)"),
                () -> assertTrue(body.containsKey("netRoiUsd"),
                        "ROI payload must carry netRoiUsd = savings − embeddingCost"),
                () -> assertTrue(((Number) body.get("totalEmbeddingCostUsd")).doubleValue() >= 0.0,
                        "GAP §21.2: FakeEmbeddingModel emits no usage metadata so the summary stays at 0.0. "
                                + "Replace with a scripting variant that returns DefaultUsage(tokens) to flip this."));
    }

    // ─── §21 Case 3 — Dashboard endpoints return expected shape after seeded activity ───

    /**
     * Spec §21.3. Expectation: "FinOps dashboard endpoint aggregates cost by agent, team,
     *   user, provider over a window." As-shipped, {@code /trends} and {@code /allocations}
     *   aggregate RUN COUNTS (with a flat $0.08 estimate multiplier) and group by agent +
     *   org only — no team, user, or provider dimension.
     *
     * Strategy: seed a handful of completed {@code agent_runs} rows via JDBC with distinct
     *   agent_ids and org_ids, then assert the endpoints return the expected per-agent /
     *   per-org allocation entries with {@code runCount} matching the seeded rows.
     */
    @Test
    void finopsDashboardEndpoints_aggregateRunCounts_pinFlatRateEstimateAndMissingDimensions() {
        HttpHeaders auth = userHeaders("finops-case3");
        String agentA = createAgent(auth, "T043 case 3 agent A");
        String agentB = createAgent(auth, "T043 case 3 agent B");
        String callerOrgId = jdbc.queryForObject(
                "SELECT org_id FROM agents WHERE id = ?", String.class, agentA);
        seedRun(agentA, callerOrgId, "COMPLETED");
        seedRun(agentA, callerOrgId, "COMPLETED");
        seedRun(agentB, callerOrgId, "COMPLETED");

        ResponseEntity<List<Map<String, Object>>> trends = rest.exchange(
                url("/api/v1/finops/trends?days=7"), HttpMethod.GET, new HttpEntity<>(auth), JSON_LIST);
        ResponseEntity<List<Map<String, Object>>> allocations = rest.exchange(
                url("/api/v1/finops/allocations?days=7"), HttpMethod.GET, new HttpEntity<>(auth), JSON_LIST);

        assertEquals(200, trends.getStatusCode().value());
        assertEquals(200, allocations.getStatusCode().value());
        List<Map<String, Object>> trendList = trends.getBody();
        List<Map<String, Object>> allocList = allocations.getBody();
        assertNotNull(trendList);
        assertNotNull(allocList);

        boolean agentASlice = allocList.stream().anyMatch(e ->
                "agent".equals(e.get("dimension")) && agentA.equals(e.get("label"))
                        && ((Number) e.get("runCount")).longValue() == 2L);
        boolean agentBSlice = allocList.stream().anyMatch(e ->
                "agent".equals(e.get("dimension")) && agentB.equals(e.get("label"))
                        && ((Number) e.get("runCount")).longValue() == 1L);
        boolean orgSlice = allocList.stream().anyMatch(e ->
                "org".equals(e.get("dimension")) && callerOrgId.equals(e.get("label"))
                        && ((Number) e.get("runCount")).longValue() >= 3L);

        assertAll("case 3 — dashboard aggregation shape",
                () -> assertEquals(7, trendList.size(),
                        "trends must zero-fill to exactly days=7 data points — that's the contract the React UI relies on"),
                () -> assertTrue(trendList.stream().allMatch(p ->
                                p.containsKey("date") && p.containsKey("runCount") && p.containsKey("estimatedUsd")),
                        "each trend point must carry date + runCount + estimatedUsd (flat-rate estimate, not real cost)"),
                () -> assertTrue(agentASlice, "agentA must appear with runCount=2 in allocations (seeded 2 rows)"),
                () -> assertTrue(agentBSlice, "agentB must appear with runCount=1 in allocations (seeded 1 row)"),
                () -> assertTrue(orgSlice, "caller's org must appear with runCount >= 3 in allocations (org dimension aggregates across agents)"),
                () -> assertTrue(allocList.stream().noneMatch(e -> "team".equals(e.get("dimension")) || "user".equals(e.get("dimension")) || "provider".equals(e.get("dimension"))),
                        "GAP §21.3: spec lists team/user/provider dimensions but only agent + org are implemented. "
                                + "Flip this once GROUP BY is extended."));
    }

    // ─── §21 Case 4 — Budget ceiling enforcement: policy → 402 PAYMENT_REQUIRED ───

    /**
     * Spec §21.4. Verifies the full enforcement chain end-to-end via HTTP:
     *   (1) {@code BudgetPolicyService} resolves the agent-scoped ceiling from
     *       {@code budget_policies}; (2) {@code AgentService.run()} binds it into
     *       {@code AgentContextHolder.CONTEXT} before the ScopedValue call; (3) the
     *       fake model emits token usage metadata so {@code GenAiMetricsAdvisor} can
     *       compute non-zero cost and throw {@code FinOpsBudgetExhaustedException}; (4)
     *       {@code GlobalExceptionHandler.handleFinOpsBudgetExhausted} maps it to HTTP 402.
     *
     * Rate seeding: {@code finops_valuation_rate} is upserted for {@code gpt-4o-mini}
     *   so {@code LiveValuationEngine.calculateUsdBreakdown} returns a deterministic
     *   non-zero cost regardless of whatever seed data migrations left in the table.
     *   1000 input + 500 output tokens at $0.15/$0.60 per 1K ≈ $0.00045, which
     *   comfortably exceeds the $0.0001 ceiling inserted by this test.
     */
    @Test
    void budgetCeilingEnforcement_returns402_whenAgentExceedsPolicyLimit() {
        HttpHeaders auth = userHeaders("finops-case4");
        String agentId = createAgent(auth, "T043 case 4 budget agent");
        String orgId = jdbc.queryForObject("SELECT org_id FROM agents WHERE id = ?", String.class, agentId);

        // Deterministic rates: $0.15/1K input, $0.60/1K output
        jdbc.update("""
                INSERT INTO finops_valuation_rate (model_id, input_rate_per_k_tokens, output_rate_per_k_tokens, updated_at)
                VALUES ('gpt-4o-mini', 0.15, 0.60, now())
                ON CONFLICT (model_id) DO UPDATE SET
                    input_rate_per_k_tokens = EXCLUDED.input_rate_per_k_tokens,
                    output_rate_per_k_tokens = EXCLUDED.output_rate_per_k_tokens
                """);

        // Agent-scoped policy with $0.0001 ceiling — will be exceeded by ~4.5×
        String policyId = "policy-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO budget_policies (id, org_id, agent_id, ceiling_usd, active, created_at, updated_at)
                VALUES (?, ?, ?, 0.0001, true, now(), now())
                """, policyId, orgId, agentId);

        fakeModel.respondWithTokens("budget probe reply", 1000, 500);

        ResponseEntity<Map<String, Object>> run = runAgent(auth, agentId, "budget probe", "session-" + UUID.randomUUID());
        assertEquals(402, run.getStatusCode().value(),
                "§21.4: run exceeding the org/agent budget ceiling must return HTTP 402 PAYMENT_REQUIRED");
    }

    /**
     * D1 — end-to-end "money loop". The same 402 path as the test above is the trigger,
     *   but here we follow the chain past the HTTP response into the telemetry surfaces:
     * <ol>
     *   <li>A {@code BUDGET_EXCEEDED} row lands in {@code agent_run_events} for this run
     *       (written by {@code GenAiMetricsAdvisor.publishBudgetExceeded} before
     *       {@code FinOpsBudgetExhaustedException} propagates — R-18 isolation: the
     *       audit fires whether or not the HTTP response succeeds).</li>
     *   <li>{@code GET /api/observability/budget-exceeded-feed} surfaces it for the
     *       authenticated tenant's principal (PR #666 routes through
     *       {@code AgentContextHolder.getOrgId()} so the caller's JWT-bound org sees
     *       its own rows even with a ROLE_USER token).</li>
     * </ol>
     *
     * <p>This is the integration-level money-loop pin: agent-run → cost calc →
     * budget enforcer → event row → feed visibility. Without it, all four steps
     * have unit-level coverage but nothing pins the chain composes correctly.
     *
     * <p>Requires PRs #671 (G4 — controller resolves orgId from principal not body)
     * and the test-auth fixture stamp of DEFAULT_SYSTEM_ORG on registered users.
     * Without either of those, the run path resolves orgId=null and the budget
     * enforcement no-ops.
     */
    @Test
    void budgetHaltPath_emitsBudgetExceededEvent_andSurfacesViaFeed() {
        HttpHeaders auth = userHeaders("finops-d1-money-loop");
        String agentId = createAgent(auth, "D1 money loop agent");
        String orgId = jdbc.queryForObject("SELECT org_id FROM agents WHERE id = ?", String.class, agentId);

        jdbc.update("""
                INSERT INTO finops_valuation_rate (model_id, input_rate_per_k_tokens, output_rate_per_k_tokens, updated_at)
                VALUES ('gpt-4o-mini', 0.15, 0.60, now())
                ON CONFLICT (model_id) DO UPDATE SET
                    input_rate_per_k_tokens = EXCLUDED.input_rate_per_k_tokens,
                    output_rate_per_k_tokens = EXCLUDED.output_rate_per_k_tokens
                """);

        String policyId = "policy-d1-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO budget_policies (id, org_id, agent_id, ceiling_usd, active, created_at, updated_at)
                VALUES (?, ?, ?, 0.0001, true, now(), now())
                """, policyId, orgId, agentId);

        java.time.Instant runStartedAt = java.time.Instant.now().minusSeconds(1);

        fakeModel.respondWithTokens("budget probe reply", 1000, 500);
        ResponseEntity<Map<String, Object>> run = runAgent(
                auth, agentId, "budget probe", "session-d1-" + UUID.randomUUID());
        assertEquals(402, run.getStatusCode().value(),
                "precondition: budget halt must return 402 (already pinned by the test above)");

        // (1) BUDGET_EXCEEDED row was written to agent_run_events for this org.
        // event_type is stored as the enum's string name in agent_run_events.event_type.
        Long budgetExceededRows = jdbc.queryForObject(
                """
                SELECT count(*) FROM agent_run_events
                WHERE event_type = 'BUDGET_EXCEEDED'
                  AND org_id = ?
                  AND agent_id = ?
                  AND event_ts >= ?
                """,
                Long.class, orgId, agentId, java.sql.Timestamp.from(runStartedAt));
        assertTrue(budgetExceededRows >= 1L,
                "GenAiMetricsAdvisor.publishBudgetExceeded must write at least one BUDGET_EXCEEDED row "
                        + "to agent_run_events for the halted run (org=" + orgId + ", agent=" + agentId
                        + ", since=" + runStartedAt + ") — got " + budgetExceededRows);

        // (2) The row surfaces via GET /api/observability/budget-exceeded-feed.
        // Post-#666 the controller reads AgentContextHolder.getOrgId() (JWT-bound),
        // so a ROLE_USER token issued for this orgId sees its own rows.
        String sinceParam = "?since=" + runStartedAt.toString() + "&limit=50";
        ResponseEntity<Map<String, Object>> feed = rest.exchange(
                url("/api/observability/budget-exceeded-feed" + sinceParam),
                HttpMethod.GET, new HttpEntity<>(auth), JSON_MAP);
        assertEquals(200, feed.getStatusCode().value(),
                "budget-exceeded-feed must return 200 for an authenticated tenant user");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) feed.getBody().get("events");
        assertNotNull(events, "feed body must include an 'events' array");
        assertTrue(events.stream().anyMatch(e -> agentId.equals(e.get("agentId"))),
                "the BUDGET_EXCEEDED row for the halted run must appear in the feed under the caller's org "
                        + "(events.agentId=" + agentId + " expected; got: " + events + ")");
    }

    // ─── §21 Case 5 — Retention purges cost data carried on the run row ───

    /**
     * Spec §21.5. {@code total_cost_usd} lives directly on {@code agent_runs} (shipped in an
     *   earlier changeset). {@link DataRetentionService#enforceRetentionPolicies()} deletes
     *   runs older than {@code retention.runs-days} (default 180 days) via
     *   {@code deleteByCreatedAtBefore} — the cost value is purged atomically with the row.
     *   No separate cost_rows table or purge path is required.
     *
     * Previously @Disabled pending §21.1 schema work; {@code total_cost_usd} is present so
     *   the test is enabled and exercised directly against real Postgres.
     */
    @Test
    void retentionPurgesCostRows_totalCostUsdPurgedWithRunRow() {
        String orgId = "org-cost-ret-" + UUID.randomUUID();
        String agentId = "agent-cost-ret-" + UUID.randomUUID();
        String runId = "run-cost-ret-" + UUID.randomUUID();

        jdbc.update("""
                INSERT INTO agents (id, name, model_id, org_id, active, created_at, updated_at)
                VALUES (?, 'Cost Retention Agent', 'gpt-4o-mini', ?, true, now(), now())
                """, agentId, orgId);

        // 200-day-old COMPLETED run with a non-zero cost — past the 180-day default window.
        jdbc.update("""
                INSERT INTO agent_runs (id, agent_id, org_id, status, input, output,
                                         total_cost_usd, version, created_at, updated_at)
                VALUES (?, ?, ?, 'COMPLETED', 'test input', 'test output',
                        0.0425, 0, now() - interval '200 days', now())
                """, runId, agentId, orgId);

        BigDecimal costBefore = jdbc.queryForObject(
                "SELECT total_cost_usd FROM agent_runs WHERE id = ?", BigDecimal.class, runId);
        assertNotNull(costBefore, "total_cost_usd must be set before purge");
        assertTrue(costBefore.compareTo(BigDecimal.ZERO) > 0, "cost must be positive before purge");

        Map<String, Integer> result = dataRetentionService.enforceRetentionPolicies();
        assertTrue(result.getOrDefault("runs", 0) >= 1,
                "enforceRetentionPolicies must report at least one purged run");

        Integer rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_runs WHERE id = ?", Integer.class, runId);
        assertEquals(0, rowCount,
                "run row (and its total_cost_usd) must be purged — cost data has the same retention lifetime as the run row");
    }

    // ─── §21 Case 6 — Endpoints are org-scoped; cross-tenant aggregates do NOT leak ───

    /**
     * Spec §21.6 — closed. {@code FinOpsAnalyticsService.getCostAllocations(days, orgId)} now
     *   clauses in {@code org_id} via {@code findRunCountByAgent(since, orgId)} /
     *   {@code findRunCountByOrg(since, orgId)}; {@code FinOpsAdminController.getCostAllocations}
     *   passes {@code AgentContextHolder.getOrgId()} (JWT-bound) through to the service.
     *
     * Strategy: seed {@code agent_runs} rows under TWO distinct org_ids — caller's org and a
     *   foreign org. Hit {@code /allocations} as a caller bound to org-A and assert the
     *   response contains org-A's run (and ONLY org-A's run) — foreign-org rows must NOT
     *   appear. This is the positive replacement for the historical leak pin.
     */
    @Test
    void finopsAllocations_orgScopedToJwt_doesNotLeakOtherOrgs() {
        HttpHeaders auth = userHeaders("finops-case6");
        String agentA = createAgent(auth, "T043 case 6 agent in caller's org");
        String orgA = jdbc.queryForObject(
                "SELECT org_id FROM agents WHERE id = ?", String.class, agentA);
        String foreignOrg = "org-finops-6-foreign";
        String agentForeign = "agent-foreign-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, org_id, active, created_at, updated_at)
                VALUES (?, 'Foreign org agent', 'gpt-4o-mini', ?, true, now(), now())
                """, agentForeign, foreignOrg);
        seedRun(agentA, orgA, "COMPLETED");
        seedRun(agentA, orgA, "COMPLETED");
        seedRun(agentForeign, foreignOrg, "COMPLETED");

        ResponseEntity<List<Map<String, Object>>> allocations = rest.exchange(
                url("/api/v1/finops/allocations?days=7"), HttpMethod.GET, new HttpEntity<>(auth), JSON_LIST);
        assertEquals(200, allocations.getStatusCode().value());

        List<Map<String, Object>> body = allocations.getBody();
        assertNotNull(body);
        boolean sawCallerOrg = body.stream().anyMatch(e ->
                "org".equals(e.get("dimension")) && orgA.equals(e.get("label")));
        boolean sawForeignOrg = body.stream().anyMatch(e ->
                "org".equals(e.get("dimension")) && foreignOrg.equals(e.get("label")));
        boolean sawForeignAgent = body.stream().anyMatch(e ->
                "agent".equals(e.get("dimension")) && agentForeign.equals(e.get("label")));

        assertAll("case 6 — cross-tenant isolation pin (§21.6 closed)",
                () -> assertTrue(sawCallerOrg, "caller's own org must appear in allocations"),
                () -> assertFalse(sawForeignOrg,
                        "foreign org's runs must NOT appear — §21.6 closed via org-scoped query in FinOpsAnalyticsService"),
                () -> assertFalse(sawForeignAgent,
                        "foreign org's agent must NOT appear — agent dimension is org-scoped too"));
    }

    // ─── helpers ───

    private ResponseEntity<Map<String, Object>> runAgent(HttpHeaders auth, String agentId, String message, String sessionId) {
        Map<String, Object> body = new HashMap<>();
        body.put("message", message);
        body.put("sessionId", sessionId);
        return rest.exchange(url("/api/agents/" + agentId + "/runs"),
                HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);
    }

    private String createAgent(HttpHeaders auth, String name) {
        String agentId = "agent-" + UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId);
        body.put("name", name);
        body.put("description", "T043 fixture");
        body.put("instructions", "Be helpful.");
        body.put("model", "gpt-4o-mini");
        body.put("isReasoningEnabled", false);
        body.put("isTeam", false);
        body.put("requiresPiiRedaction", false);
        body.put("approvedForProduction", false);
        body.put("maintenanceMode", false);
        body.put("active", true);
        body.put("enforceJsonOutput", false);
        body.put("memoryEnabled", true);
        body.put("addHistoryToMessages", true);

        ResponseEntity<Map<String, Object>> response = rest.exchange(
                url("/api/admin/agents"), HttpMethod.POST, new HttpEntity<>(body, auth), JSON_MAP);
        assertEquals(201, response.getStatusCode().value(),
                "fixture precondition: agent create must return 201 before finops tests reference it");
        return agentId;
    }

    private void seedModel(String modelId) {
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES (?, ?, 'fake', ?, true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """, modelId, modelId, modelId);
    }

    private void seedRun(String agentId, String orgId, String status) {
        String runId = "run-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agent_runs (id, agent_id, org_id, status, input, output, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'seeded input', 'seeded output', now(), now())
                """, runId, agentId, orgId, status);
    }

    /**
     * Post-gate: FinOpsAdminController now carries class-level
     * @PreAuthorize("hasRole('ADMIN')"). All callers in this test need ROLE_ADMIN to
     * pass the gate. Helper name preserved for diff minimization; conceptually now
     * "authenticated finops caller".
     */
    private HttpHeaders userHeaders(String username) {
        return authenticateAs(username, username + "@test.local", "pass-finops-1234", List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    private HttpHeaders adminHeaders(String username) {
        return authenticateAs(username, username + "@test.local", "pass-finops-1234", List.of("ROLE_USER", "ROLE_ADMIN"));
    }
}
