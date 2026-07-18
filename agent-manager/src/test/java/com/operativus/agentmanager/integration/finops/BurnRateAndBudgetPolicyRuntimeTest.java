package com.operativus.agentmanager.integration.finops;

import com.operativus.agentmanager.control.finops.service.BudgetPolicyService;
import com.operativus.agentmanager.control.finops.service.BurnRateMonitorService;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Runtime pins for two previously-uncovered FinOps surfaces:
 *     <ol>
 *       <li><b>{@link BurnRateMonitorService}</b> — sliding-window spend accumulator that
 *           detects anomalies when {@code burnRate / baseline >= anomalyMultiplierThreshold}
 *           (default {@code 5.0×}). Before this test, the service had only unit-level checks;
 *           no runtime confirmation that the spend → anomaly path actually fires.</li>
 *       <li><b>{@link BudgetPolicyService.findActiveCeiling}</b> — multi-tier policy
 *           resolution (agent-scoped wins over org-scoped, none → empty). The
 *           {@code FinOpsRuntimeTest.budgetCeilingEnforcement_...} pin only covered the
 *           agent-scoped happy path; the precedence rules across tiers were unpinned.</li>
 *     </ol>
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Tag("integration")
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class BurnRateAndBudgetPolicyRuntimeTest extends BaseIntegrationTest {

    @Autowired private BurnRateMonitorService burnRateMonitor;
    @Autowired private BudgetPolicyService budgetPolicyService;

    /**
     * B5 — Burn rate anomaly detection.
     *
     * <p>Registers a $1.00/hr baseline for an agent. Records a single $1.00 spend in a fresh
     * window — that's a 60s window with $1 spent, computing to $60/hr, a 60× ratio over the
     * baseline. Default threshold is 5×, so this must surface in {@code getActiveAnomalies()}.
     */
    @Test
    void burnRateMonitor_recordSpendAboveThreshold_surfacesViaGetActiveAnomalies() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String agentId = "agent-burn-" + tag;
        String sessionId = "session-burn-" + tag;

        burnRateMonitor.registerBaseline(agentId, 1.00);
        burnRateMonitor.recordSpend(sessionId, agentId, 1.00);

        var anomalies = burnRateMonitor.getActiveAnomalies();
        assertTrue(anomalies.stream().anyMatch(a -> sessionId.equals(a.sessionId())),
                "session must appear in getActiveAnomalies() — a $1 spend in a fresh 60s window "
                        + "is $60/hr vs $1/hr baseline (60× ratio, well above the 5× threshold). "
                        + "Got: " + anomalies);

        var anomaly = anomalies.stream()
                .filter(a -> sessionId.equals(a.sessionId())).findFirst().orElseThrow();
        assertEquals(agentId, anomaly.agentId(), "anomaly must carry the recorded agentId");
        assertTrue(anomaly.anomalyRatio() >= 5.0,
                "anomalyRatio must be >= 5× threshold; got " + anomaly.anomalyRatio());

        burnRateMonitor.evictSession(sessionId);
    }

    /**
     * B5-baseline — Below-threshold spend does NOT surface as an anomaly.
     *
     * <p>Symmetric counter-test: with the immediate-record math (elapsed ≈ 1s) the burn rate
     * is computed as {@code spend / (1/3600)} = {@code spend × 3600}. So a $0.0001 spend
     * yields $0.36/hr. Against a $1/hr baseline that's a 0.36× ratio — well under the
     * default 5× threshold. Confirms the anomaly filter actually filters; protects against
     * a regression where every recorded spend leaks into anomalies.
     */
    @Test
    void burnRateMonitor_recordSpendBelowThreshold_doesNotSurfaceAsAnomaly() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String agentId = "agent-quiet-" + tag;
        String sessionId = "session-quiet-" + tag;

        burnRateMonitor.registerBaseline(agentId, 1.00);
        burnRateMonitor.recordSpend(sessionId, agentId, 0.0001);

        assertFalse(
                burnRateMonitor.getActiveAnomalies().stream()
                        .anyMatch(a -> sessionId.equals(a.sessionId())),
                "session under threshold must NOT appear in getActiveAnomalies() — "
                        + "regression guard against the filter being dropped");

        burnRateMonitor.evictSession(sessionId);
    }

    /**
     * B6a — Multi-tier resolution: agent-scoped policy WINS over org-scoped.
     *
     * <p>Both policies active: org-scoped $10.00 + agent-scoped $0.0001 for the same agent.
     * {@code findActiveCeiling(orgId, agentId)} must return the agent-scoped $0.0001 (the
     * more-restrictive operator contract). The {@code ORDER BY agentId NULLS LAST} clause
     * in {@code BudgetPolicyRepository.findActivePolicyForAgentOrOrg} is what pins this.
     */
    @Test
    void budgetPolicyResolution_agentScopedPolicy_winsOverOrgScoped() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String orgId = "org-bp-" + tag;
        String agentId = "agent-bp-" + tag;

        insertPolicy("policy-org-" + tag, orgId, null, 10.00);
        insertPolicy("policy-agent-" + tag, orgId, agentId, 0.0001);

        Optional<Double> ceiling = budgetPolicyService.findActiveCeiling(orgId, agentId);
        assertTrue(ceiling.isPresent(), "active ceiling must be resolved when policies exist");
        assertEquals(0.0001, ceiling.get(), 0.00001,
                "agent-scoped policy must win over org-scoped — got " + ceiling.get());
    }

    /**
     * B6b — Multi-tier resolution: org-only policy applies when no agent-scoped row exists.
     */
    @Test
    void budgetPolicyResolution_orgScopedOnly_appliesWhenNoAgentPolicy() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String orgId = "org-bp-org-only-" + tag;
        String agentId = "agent-bp-org-only-" + tag;

        insertPolicy("policy-org-only-" + tag, orgId, null, 5.00);

        Optional<Double> ceiling = budgetPolicyService.findActiveCeiling(orgId, agentId);
        assertTrue(ceiling.isPresent(), "org-scoped policy must apply as fallback when no agent policy exists");
        assertEquals(5.00, ceiling.get(), 0.00001, "got " + ceiling.get());
    }

    /**
     * B6c — Multi-tier resolution: no policies → empty Optional (unbounded execution).
     */
    @Test
    void budgetPolicyResolution_noPolicies_returnsEmptyOptional() {
        String orgId = "org-bp-none-" + UUID.randomUUID();
        String agentId = "agent-bp-none-" + UUID.randomUUID();

        Optional<Double> ceiling = budgetPolicyService.findActiveCeiling(orgId, agentId);
        assertTrue(ceiling.isEmpty(),
                "no active policies for org/agent must yield empty Optional — caller treats as unbounded");
    }

    /**
     * B6d — Inactive policies are ignored even if they match org+agent.
     *
     * <p>Pins the {@code AND p.active = true} clause: a policy row that was deactivated must
     * not resolve. Without this guard, a tombstoned row would silently keep enforcing.
     */
    @Test
    void budgetPolicyResolution_inactivePolicy_isIgnored() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String orgId = "org-bp-inactive-" + tag;
        String agentId = "agent-bp-inactive-" + tag;

        jdbc.update("""
                INSERT INTO budget_policies (id, org_id, agent_id, ceiling_usd, active, created_at, updated_at)
                VALUES (?, ?, ?, 0.0001, false, now(), now())
                """, "policy-inactive-" + tag, orgId, agentId);

        Optional<Double> ceiling = budgetPolicyService.findActiveCeiling(orgId, agentId);
        assertTrue(ceiling.isEmpty(),
                "inactive policy must be excluded from active resolution; got " + ceiling);
    }

    private void insertPolicy(String policyId, String orgId, String agentId, double ceilingUsd) {
        jdbc.update("""
                INSERT INTO budget_policies (id, org_id, agent_id, ceiling_usd, active, created_at, updated_at)
                VALUES (?, ?, ?, ?, true, now(), now())
                """, policyId, orgId, agentId, ceilingUsd);
    }
}
