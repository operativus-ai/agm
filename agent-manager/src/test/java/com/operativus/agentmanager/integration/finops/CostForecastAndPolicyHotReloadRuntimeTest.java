package com.operativus.agentmanager.integration.finops;

import com.operativus.agentmanager.control.finops.service.BudgetPolicyService;
import com.operativus.agentmanager.control.finops.service.CostForecastService;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Two small FinOps runtime pins:
 *     <ol>
 *       <li><b>D9</b> — {@code CostForecastService.forecastCosts} returns a well-formed
 *           payload for the empty-data case and the populated-data case. The service is
 *           not currently wired to an HTTP endpoint, but it's a public bean used by the
 *           dashboard composition layer — runtime confirmation that its output shape is
 *           stable.</li>
 *       <li><b>D10</b> — {@code BudgetPolicyService.findActiveCeiling} reads from the
 *           database on every call (no in-memory cache). UPDATE-ing a policy row mid-test
 *           must be observable on the very next call — pins the no-cache contract that
 *           operators rely on for emergency-tightening of ceilings during incidents.</li>
 *     </ol>
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Tag("integration")
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class CostForecastAndPolicyHotReloadRuntimeTest extends BaseIntegrationTest {

    @Autowired private CostForecastService costForecastService;
    @Autowired private BudgetPolicyService budgetPolicyService;

    /**
     * D9 — {@code forecastCosts} with no run history returns the all-zero forecast shape.
     *
     * <p><b>Documented vs actual</b>: the service has a guard
     * {@code if (trends.isEmpty()) return {"error": "Insufficient data for forecasting"}}
     * but in practice {@code FinOpsAnalyticsService.getHistoricalTrends} zero-fills the
     * requested window (returns a list of empty days, never empty), so the sentinel
     * branch is unreachable. This test pins the actual observed behavior — a well-formed
     * forecast with zeros and {@code trend_direction=STABLE} — so a future change to
     * either side of that contract surfaces explicitly.
     */
    @Test
    void costForecastService_noTrailingData_returnsAllZeroForecastWithStableDirection() {
        Map<String, Object> forecast = costForecastService.forecastCosts(14);

        assertNotNull(forecast, "service must always return a non-null payload");
        assertEquals(14, forecast.get("trailing_days"));
        assertEquals(0.0, ((Number) forecast.get("avg_daily_usd")).doubleValue(), 0.0001);
        assertEquals(0.0, ((Number) forecast.get("trend_slope_usd_per_day")).doubleValue(), 0.0001);
        assertEquals("STABLE", forecast.get("trend_direction"),
                "no run history → flat/stable direction; got " + forecast);
        assertEquals(0.0, ((Number) forecast.get("projected_7d_usd")).doubleValue(), 0.0001);
        assertEquals(0.0, ((Number) forecast.get("projected_14d_usd")).doubleValue(), 0.0001);
        assertEquals(0.0, ((Number) forecast.get("projected_30d_usd")).doubleValue(), 0.0001);
    }

    /**
     * D9-shape — Populated trends produce the documented projected-cost shape.
     *
     * <p>Seed enough completed {@code agent_runs} rows over the trailing window for
     * {@code FinOpsAnalyticsService.getHistoricalTrends} to return non-empty data. Then
     * assert {@code forecastCosts} returns the documented keys: {@code trailing_days},
     * {@code avg_daily_usd}, {@code trend_slope_usd_per_day}, {@code trend_direction}.
     */
    @Test
    void costForecastService_populatedTrends_returnsDocumentedShape() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String agentId = "agent-fc-" + tag;
        jdbc.update("""
                INSERT INTO models (id, name, provider, model_name, supports_tools, supports_vision,
                                    supports_system_instructions, model_type, created_at)
                VALUES ('gpt-4o-mini', 'gpt-4o-mini', 'fake', 'gpt-4o-mini', true, false, true, 'CHAT', now())
                ON CONFLICT (id) DO NOTHING
                """);
        jdbc.update("""
                INSERT INTO agents (id, name, model_id, org_id, active, created_at, updated_at)
                VALUES (?, 'forecast probe agent', 'gpt-4o-mini', 'org-fc', true, now(), now())
                """, agentId);
        for (int day = 0; day < 7; day++) {
            for (int run = 0; run < 3; run++) {
                jdbc.update("""
                        INSERT INTO agent_runs (id, agent_id, org_id, status, input, created_at, updated_at)
                        VALUES (?, ?, 'org-fc', 'COMPLETED', 'forecast probe',
                                now() - interval '%d days', now() - interval '%d days')
                        """.formatted(day, day), "run-fc-" + tag + "-" + day + "-" + run, agentId);
            }
        }

        Map<String, Object> forecast = costForecastService.forecastCosts(14);

        assertTrue(forecast.containsKey("trailing_days"), "must carry trailing_days; got " + forecast);
        assertTrue(forecast.containsKey("avg_daily_usd"), "must carry avg_daily_usd; got " + forecast);
        assertTrue(forecast.containsKey("trend_slope_usd_per_day"),
                "must carry trend_slope_usd_per_day; got " + forecast);
        assertTrue(forecast.containsKey("trend_direction"),
                "must carry trend_direction (one of INCREASING / DECREASING / STABLE); got " + forecast);
    }

    /**
     * D10 — Budget policy hot-reload: UPDATE is observable on the very next call.
     *
     * <p>{@code BudgetPolicyService.findActiveCeiling} has no {@code @Cacheable} annotation
     * and no in-memory cache — it reads from {@code BudgetPolicyRepository} on every
     * invocation (confirmed by the {@code @Transactional(readOnly = true)}-only surface).
     * Operators rely on this for emergency-tightening of ceilings during incidents: a
     * stale cache would mean a tightened policy doesn't apply until the next app restart.
     */
    @Test
    void budgetPolicyHotReload_updateIsObservableOnNextCall_noStaleCache() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String orgId = "org-hr-" + tag;
        String agentId = "agent-hr-" + tag;
        String policyId = "policy-hr-" + tag;

        jdbc.update("""
                INSERT INTO budget_policies (id, org_id, agent_id, ceiling_usd, active, created_at, updated_at)
                VALUES (?, ?, ?, 10.00, true, now(), now())
                """, policyId, orgId, agentId);

        Optional<Double> initial = budgetPolicyService.findActiveCeiling(orgId, agentId);
        assertEquals(10.00, initial.orElseThrow(), 0.0001,
                "initial ceiling must resolve from the seeded row");

        // Operator action: emergency-tighten the ceiling.
        jdbc.update("UPDATE budget_policies SET ceiling_usd = 0.0001 WHERE id = ?", policyId);

        Optional<Double> hotReloaded = budgetPolicyService.findActiveCeiling(orgId, agentId);
        assertEquals(0.0001, hotReloaded.orElseThrow(), 0.00001,
                "tightened ceiling must be visible on the very next call — no stale cache. got "
                        + hotReloaded);
    }
}
