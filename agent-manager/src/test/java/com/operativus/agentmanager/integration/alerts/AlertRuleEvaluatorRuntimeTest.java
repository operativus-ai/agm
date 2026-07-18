package com.operativus.agentmanager.integration.alerts;

import com.operativus.agentmanager.control.repository.AlertEventRepository;
import com.operativus.agentmanager.control.repository.AlertRuleRepository;
import com.operativus.agentmanager.core.entity.AlertEvent;
import com.operativus.agentmanager.core.entity.AlertRule;
import com.operativus.agentmanager.core.model.TenantConstants;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import com.operativus.agentmanager.integration.support.SchedulerTestSupport;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Black-box pins on the per-rule evaluation logic inside
 *   {@link com.operativus.agentmanager.control.service.AlertingService#evaluateRules}.
 *   Existing {@link AlertsRuntimeTest} covers the matrix §25 happy path (single GT against
 *   a gauge, cooldown positive case, dispatch via event bus). This class fills the
 *   combinatoric gaps the evaluator exposes: every condition operator and every metric
 *   type the {@code readMetric} fallback chain supports.
 * State: Stateless. Inherits {@link BaseIntegrationTest#truncateDatabase()} between tests
 *   so alert_rules and alert_events are cleared. Micrometer meters cannot be deregistered,
 *   so every test uses a UUID-suffixed metric name to keep the registry race-free.
 *
 * Plan: {@code .claude/plans/alert-rule-runtime-coverage-2026-05-16.md} pins A1-A5, B1-B4.
 */
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class, SchedulerTestSupport.class})
public class AlertRuleEvaluatorRuntimeTest extends BaseIntegrationTest {

    @Autowired private AlertRuleRepository ruleRepository;
    @Autowired private AlertEventRepository eventRepository;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private SchedulerTestSupport scheduler;

    // ─── A: condition matrix ───

    // A1 — GTE is inclusive at the boundary: value == threshold fires.
    @Test
    void conditionGte_valueEqualsThreshold_firesEvent() {
        String metric = uniqueMetric("a1.gte.boundary");
        gauge(metric, 10);

        ruleRepository.save(rule("a1", metric, "GTE", 10.0));

        scheduler.tickAlerting();

        List<AlertEvent> events = eventRepository.findByRuleIdOrderByFiredAtDesc("a1");
        assertEquals(1, events.size(),
                "GTE 10 with metric=10 must fire — GTE is inclusive at the boundary");
        assertEquals(10.0, events.get(0).getMetricValue(), 0.001,
                "fired event's metric_value must reflect the gauge reading");
    }

    // A2 — GT is strictly exclusive at the boundary: value == threshold does NOT fire;
    //      a higher value does. Pinned in one test by flipping the gauge mid-flight.
    @Test
    void conditionGt_boundaryExclusiveThenFiresWhenValueExceeds() {
        String metric = uniqueMetric("a2.gt.boundary");
        AtomicInteger holder = new AtomicInteger(10);
        meterRegistry.gauge(metric, holder);

        ruleRepository.save(rule("a2", metric, "GT", 10.0));

        scheduler.tickAlerting();
        assertEquals(0, eventRepository.findByRuleIdOrderByFiredAtDesc("a2").size(),
                "GT 10 with metric=10 must NOT fire — GT is strict");

        holder.set(11);
        scheduler.tickAlerting();
        List<AlertEvent> after = eventRepository.findByRuleIdOrderByFiredAtDesc("a2");
        assertEquals(1, after.size(),
                "GT 10 with metric=11 must fire on the next tick");
        assertEquals(11.0, after.get(0).getMetricValue(), 0.001);
    }

    // A3 — LT and LTE symmetric pair: LT excludes the boundary, LTE includes it.
    //      Two independent rules + two metrics keeps the assertion surface clean.
    @Test
    void conditionLtAndLte_symmetricBoundaryBehaviour() {
        String metricLt = uniqueMetric("a3.lt");
        String metricLte = uniqueMetric("a3.lte");
        gauge(metricLt, 10);   // for LT
        gauge(metricLte, 10);  // for LTE

        ruleRepository.save(rule("a3-lt-eq", metricLt, "LT", 10.0));
        ruleRepository.save(rule("a3-lt-lo", metricLt, "LT", 11.0));
        ruleRepository.save(rule("a3-lte-eq", metricLte, "LTE", 10.0));

        scheduler.tickAlerting();

        assertEquals(0, eventRepository.findByRuleIdOrderByFiredAtDesc("a3-lt-eq").size(),
                "LT 10 with metric=10 must NOT fire — LT is strict");
        assertEquals(1, eventRepository.findByRuleIdOrderByFiredAtDesc("a3-lt-lo").size(),
                "LT 11 with metric=10 must fire — value strictly below threshold");
        assertEquals(1, eventRepository.findByRuleIdOrderByFiredAtDesc("a3-lte-eq").size(),
                "LTE 10 with metric=10 must fire — LTE is inclusive");
    }

    // A4 — EQ uses ±0.001 tolerance (AlertingService line ~134 uses
    //      Math.abs(value - threshold) < 0.001). Inside the window fires; outside does not.
    @Test
    void conditionEq_tolerancePlusMinusOneThousandth() {
        String metricInside = uniqueMetric("a4.eq.in");
        String metricOutside = uniqueMetric("a4.eq.out");
        // Need fractional precision so use Gauge.builder; the AtomicInteger gauge helper
        // truncates to int and would collapse the ±0.001 tolerance test into noise.
        Gauge.builder(metricInside, () -> 10.0005).register(meterRegistry);
        Gauge.builder(metricOutside, () -> 10.0015).register(meterRegistry);

        ruleRepository.save(rule("a4-in", metricInside, "EQ", 10.0));
        ruleRepository.save(rule("a4-out", metricOutside, "EQ", 10.0));

        scheduler.tickAlerting();

        assertEquals(1, eventRepository.findByRuleIdOrderByFiredAtDesc("a4-in").size(),
                "EQ 10 with metric=10.0005 must fire — within ±0.001 tolerance");
        assertEquals(0, eventRepository.findByRuleIdOrderByFiredAtDesc("a4-out").size(),
                "EQ 10 with metric=10.0015 must NOT fire — outside ±0.001 tolerance");
    }

    // A5 — Unknown condition string falls to switch default (returns false), producing no
    //      event and no exception. Proves the default arm is safe rather than throwing.
    @Test
    void conditionUnknownString_fallsToDefaultFalseAndProducesNoEvent() {
        String metric = uniqueMetric("a5.unknown");
        gauge(metric, 999);

        ruleRepository.save(rule("a5", metric, "FOO_BAR", 1.0));

        scheduler.tickAlerting();

        assertEquals(0, eventRepository.findByRuleIdOrderByFiredAtDesc("a5").size(),
                "unknown condition must fall through the switch default and produce no event");
    }

    // ─── B: metric-type fallback chain (gauge → counter → summary → timer) ───

    // B1 — Counter-backed metric: readMetric falls past gauge and reads .count().
    @Test
    void metricTypeCounter_breachReadsCountValue() {
        String metric = uniqueMetric("b1.counter");
        meterRegistry.counter(metric).increment(15.0);

        ruleRepository.save(rule("b1", metric, "GT", 10.0));

        scheduler.tickAlerting();

        List<AlertEvent> events = eventRepository.findByRuleIdOrderByFiredAtDesc("b1");
        assertEquals(1, events.size(),
                "counter with count=15 against GT 10 must fire one event");
        assertEquals(15.0, events.get(0).getMetricValue(), 0.001,
                "metric_value must be counter.count()");
    }

    // B2 — DistributionSummary: readMetric falls past gauge + counter and reads
    //      .totalAmount() — record(7) + record(8) → totalAmount=15.
    @Test
    void metricTypeSummary_breachReadsTotalAmount() {
        String metric = uniqueMetric("b2.summary");
        DistributionSummary summary = DistributionSummary.builder(metric).register(meterRegistry);
        summary.record(7);
        summary.record(8);

        ruleRepository.save(rule("b2", metric, "GT", 10.0));

        scheduler.tickAlerting();

        List<AlertEvent> events = eventRepository.findByRuleIdOrderByFiredAtDesc("b2");
        assertEquals(1, events.size(),
                "DistributionSummary with totalAmount=15 against GT 10 must fire");
        assertEquals(15.0, events.get(0).getMetricValue(), 0.001,
                "metric_value must be summary.totalAmount()");
    }

    // B3 — Timer: readMetric falls past gauge/counter/summary and reads (double) timer.count().
    //      Two record() calls → count=2.
    @Test
    void metricTypeTimer_breachReadsRecordCount() {
        String metric = uniqueMetric("b3.timer");
        Timer timer = Timer.builder(metric).register(meterRegistry);
        timer.record(Duration.ofMillis(100));
        timer.record(Duration.ofMillis(200));

        ruleRepository.save(rule("b3", metric, "GT", 1.0));

        scheduler.tickAlerting();

        List<AlertEvent> events = eventRepository.findByRuleIdOrderByFiredAtDesc("b3");
        assertEquals(1, events.size(),
                "timer with count=2 against GT 1 must fire");
        assertEquals(2.0, events.get(0).getMetricValue(), 0.001,
                "metric_value must be (double) timer.count()");
    }

    // B4 — Metric name not registered in any tier: readMetric returns null, evaluateRules
    //      continues the loop. No event, no exception leaking out of the scheduler tick.
    @Test
    void metricTypeAbsent_readMetricReturnsNullAndLoopContinues() {
        String metric = uniqueMetric("b4.absent");
        // Deliberately do NOT register any meter for this name.

        ruleRepository.save(rule("b4", metric, "GT", 0.0));

        scheduler.tickAlerting();

        assertEquals(0, eventRepository.findByRuleIdOrderByFiredAtDesc("b4").size(),
                "absent metric must not produce an event");
        assertTrue(true, "tickAlerting must not throw when readMetric returns null");
    }

    // ─── C: disabled-rule resilience ───

    // C1 — A disabled rule with a breaching metric must never fire. Proves the
    //      ruleRepository.findByEnabledTrue() filter (AlertingService line ~123) is
    //      load-bearing — if a future refactor switches to findAll(), this test fails.
    @Test
    void disabledRuleNeverFiresEvenWhenMetricBreaches() {
        String metric = uniqueMetric("c1.disabled");
        gauge(metric, 999);

        AlertRule r = rule("c1", metric, "GT", 10.0);
        r.setEnabled(false);
        ruleRepository.save(r);

        scheduler.tickAlerting();

        assertEquals(0, eventRepository.findByRuleIdOrderByFiredAtDesc("c1").size(),
                "disabled rule must not fire — findByEnabledTrue filter must hold");
    }

    // ─── D: cooldown edges ───

    // D1 — After windowSeconds elapses (simulated by backdating fired_at via JDBC), a
    //      second breach DOES fire. The positive cooldown case is already pinned in
    //      AlertsRuntimeTest.cooldownPreventsDuplicateAlertsWithinWindow; this is the
    //      complementary negative case: cooldown expires → next tick fires fresh.
    @Test
    void cooldownExpiration_secondBreachFiresAfterWindowLapses() {
        String metric = uniqueMetric("d1.cooldown.expire");
        gauge(metric, 999);

        AlertRule r = rule("d1", metric, "GT", 10.0);
        r.setWindowSeconds(60);
        ruleRepository.save(r);

        scheduler.tickAlerting();
        assertEquals(1, eventRepository.findByRuleIdOrderByFiredAtDesc("d1").size(),
                "first tick must fire (cooldown window empty)");

        scheduler.tickAlerting();
        assertEquals(1, eventRepository.findByRuleIdOrderByFiredAtDesc("d1").size(),
                "second tick inside cooldown window must NOT fire");

        // Backdate the existing event so the cooldown floor (now - 60s) sits AFTER its
        // fired_at — existsByRuleIdAndFiredAtAfter returns false → next breach fires.
        int updated = jdbc.update(
                "UPDATE alert_events SET fired_at = ? WHERE rule_id = ?",
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusSeconds(120)),
                "d1");
        assertEquals(1, updated, "precondition: exactly one event row to backdate");

        scheduler.tickAlerting();
        assertEquals(2, eventRepository.findByRuleIdOrderByFiredAtDesc("d1").size(),
                "tick after cooldown window expires must fire fresh event");
    }

    // D2 — windowSeconds=0 short-circuits the cooldown lookup (AlertingService line ~142
    //      guards with `if (windowSeconds > 0)`). Every tick produces an event.
    @Test
    void cooldownZeroWindow_everyTickFiresFreshEvent() {
        String metric = uniqueMetric("d2.cooldown.zero");
        gauge(metric, 999);

        AlertRule r = rule("d2", metric, "GT", 10.0);
        r.setWindowSeconds(0);
        ruleRepository.save(r);

        scheduler.tickAlerting();
        scheduler.tickAlerting();
        scheduler.tickAlerting();

        assertEquals(3, eventRepository.findByRuleIdOrderByFiredAtDesc("d2").size(),
                "windowSeconds=0 must disable cooldown — every tick fires a fresh event");
    }

    // D3 — Cooldown is per-rule, not global: two rules breaching the same tick each fire,
    //      both enter independent cooldowns. Backdating R1's event releases only R1.
    @Test
    void cooldownIsPerRule_notGlobal() {
        String metric1 = uniqueMetric("d3.r1");
        String metric2 = uniqueMetric("d3.r2");
        gauge(metric1, 999);
        gauge(metric2, 999);

        AlertRule r1 = rule("d3-r1", metric1, "GT", 10.0);
        r1.setWindowSeconds(60);
        ruleRepository.save(r1);
        AlertRule r2 = rule("d3-r2", metric2, "GT", 10.0);
        r2.setWindowSeconds(60);
        ruleRepository.save(r2);

        scheduler.tickAlerting();
        assertEquals(1, eventRepository.findByRuleIdOrderByFiredAtDesc("d3-r1").size(),
                "first tick fires R1");
        assertEquals(1, eventRepository.findByRuleIdOrderByFiredAtDesc("d3-r2").size(),
                "first tick fires R2");

        scheduler.tickAlerting();
        assertEquals(1, eventRepository.findByRuleIdOrderByFiredAtDesc("d3-r1").size(),
                "R1 still in cooldown");
        assertEquals(1, eventRepository.findByRuleIdOrderByFiredAtDesc("d3-r2").size(),
                "R2 still in cooldown");

        // Backdate ONLY R1's event past its cooldown floor. R2 stays in cooldown.
        int updated = jdbc.update(
                "UPDATE alert_events SET fired_at = ? WHERE rule_id = ?",
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusSeconds(120)),
                "d3-r1");
        assertEquals(1, updated, "precondition: only R1's row backdated");

        scheduler.tickAlerting();
        assertEquals(2, eventRepository.findByRuleIdOrderByFiredAtDesc("d3-r1").size(),
                "R1 re-fires after its cooldown expires");
        assertEquals(1, eventRepository.findByRuleIdOrderByFiredAtDesc("d3-r2").size(),
                "R2 cooldown is independent of R1 — must NOT re-fire");
    }

    // ─── E: multi-tenant evaluator scoping ───

    // E1 — Evaluator stamps the parent rule's orgId onto the new event (AlertingService
    //      line ~161: event.setOrgId(rule.getOrgId())). Two rules in different orgs
    //      breaching the same tick produce events with the correct per-tenant stamp.
    @Test
    void evaluatorStampsParentRuleOrgIdOntoEvent_perTenant() {
        String metric = uniqueMetric("e1.shared");
        gauge(metric, 999);

        AlertRule rA = rule("e1-org-a", metric, "GT", 10.0);
        rA.setOrgId("org-a-e1");
        ruleRepository.save(rA);

        AlertRule rB = rule("e1-org-b", metric, "GT", 10.0);
        rB.setOrgId("org-b-e1");
        ruleRepository.save(rB);

        scheduler.tickAlerting();

        List<AlertEvent> aEvents = eventRepository.findByRuleIdOrderByFiredAtDesc("e1-org-a");
        List<AlertEvent> bEvents = eventRepository.findByRuleIdOrderByFiredAtDesc("e1-org-b");
        assertEquals(1, aEvents.size(), "org-a rule fires one event");
        assertEquals(1, bEvents.size(), "org-b rule fires one event");
        assertEquals("org-a-e1", aEvents.get(0).getOrgId(),
                "evaluator must copy rule.orgId onto event for tenant-end-to-end scoping");
        assertEquals("org-b-e1", bEvents.get(0).getOrgId(),
                "second rule's event must carry its own org, not first rule's");
    }

    // ─── F: hot-reload parity ───

    // F1 — Raising the threshold mid-cycle stops future fires without a service restart.
    //      Verifies AlertingService re-reads rule state every tick (no in-memory cache).
    @Test
    void hotReloadThresholdRaisedMidCycle_stopsFiring() {
        String metric = uniqueMetric("f1.threshold");
        gauge(metric, 15);

        AlertRule r = rule("f1", metric, "GT", 10.0);
        r.setWindowSeconds(60);
        ruleRepository.save(r);

        scheduler.tickAlerting();
        assertEquals(1, eventRepository.findByRuleIdOrderByFiredAtDesc("f1").size(),
                "first tick fires (15 > 10)");

        // Raise the threshold above the current gauge reading and save back.
        AlertRule reloaded = ruleRepository.findById("f1").orElseThrow();
        reloaded.setThreshold(20.0);
        ruleRepository.save(reloaded);

        // Backdate cooldown so it is not the reason no new event fires.
        jdbc.update(
                "UPDATE alert_events SET fired_at = ? WHERE rule_id = ?",
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusSeconds(120)),
                "f1");

        scheduler.tickAlerting();
        assertEquals(1, eventRepository.findByRuleIdOrderByFiredAtDesc("f1").size(),
                "tick after threshold raised must NOT fire (15 not > 20) — hot-reload picked up new threshold");
    }

    // F2 — Flipping enabled=true → false mid-cycle stops fires on the next tick. Confirms
    //      findByEnabledTrue() observes the updated state without service restart.
    @Test
    void hotReloadEnabledFlippedFalseMidCycle_stopsFiring() {
        String metric = uniqueMetric("f2.enabled");
        gauge(metric, 999);

        AlertRule r = rule("f2", metric, "GT", 10.0);
        r.setWindowSeconds(60);
        ruleRepository.save(r);

        scheduler.tickAlerting();
        assertEquals(1, eventRepository.findByRuleIdOrderByFiredAtDesc("f2").size(),
                "first tick fires");

        AlertRule reloaded = ruleRepository.findById("f2").orElseThrow();
        reloaded.setEnabled(false);
        ruleRepository.save(reloaded);

        // Backdate cooldown so disabled-state is the only thing that could suppress.
        jdbc.update(
                "UPDATE alert_events SET fired_at = ? WHERE rule_id = ?",
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusSeconds(120)),
                "f2");

        scheduler.tickAlerting();
        assertEquals(1, eventRepository.findByRuleIdOrderByFiredAtDesc("f2").size(),
                "tick after enabled=false must NOT fire — hot-reload picked up disabled flag");
    }

    // ─── helpers ───

    private static String uniqueMetric(String prefix) {
        return "ar1." + prefix + "." + UUID.randomUUID().toString().substring(0, 8);
    }

    private void gauge(String metricName, int value) {
        meterRegistry.gauge(metricName, new AtomicInteger(value));
    }

    private AlertRule rule(String id, String metricName, String condition, double threshold) {
        AlertRule r = new AlertRule();
        r.setId(id);
        r.setName("ar1-" + id);
        r.setMetricName(metricName);
        r.setCondition(condition);
        r.setThreshold(threshold);
        r.setWindowSeconds(60);
        r.setSeverity("WARNING");
        r.setEnabled(true);
        // Direct repository.save bypasses AlertingService.createRule, so we stamp the
        // tenant ourselves; the evaluator copies rule.orgId onto the event row.
        r.setOrgId(TenantConstants.DEFAULT_SYSTEM_ORG);
        return r;
    }
}
