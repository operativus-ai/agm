package com.operativus.agentmanager.integration.finops;

import com.operativus.agentmanager.control.finops.model.FinOpsRecords.ModelValuationRate;
import com.operativus.agentmanager.control.finops.service.BurnRateMonitorService;
import com.operativus.agentmanager.control.finops.service.LiveValuationEngine;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Runtime pins for FinOps correctness under stress:
 *     <ol>
 *       <li><b>C7</b> — {@code BurnRateMonitorService.recordSpend} accumulates correctly
 *           under concurrent recordSpend calls on the same session. Pins the
 *           {@code WindowAccumulator}'s {@code synchronized} block — a regression that
 *           dropped synchronization would surface as a flaky sub-total here.</li>
 *       <li><b>C8a</b> — {@code LiveValuationEngine.calculateUsd} for a model with NO
 *           rate row falls back to {@code DEFAULT_RATE} ($1/k input, $2/k output) — does
 *           NOT silently bill at $0. The conservative default is a financial-accounting
 *           guardrail.</li>
 *       <li><b>C8b</b> — {@code LiveValuationEngine} with an explicit zero-rate model
 *           computes $0 cost without throwing. A "free tier" model must not halt budget
 *           enforcement (cost is just 0 against any positive ceiling).</li>
 *     </ol>
 * State: Stateless (per-test isolation via {@link BaseIntegrationTest#truncateDatabase()}).
 */
@Tag("integration")
@Import({FakeChatModelConfig.class, FakeEmbeddingModelConfig.class})
public class BudgetConcurrencyAndRateEdgesRuntimeTest extends BaseIntegrationTest {

    @Autowired private BurnRateMonitorService burnRateMonitor;
    @Autowired private LiveValuationEngine valuationEngine;

    /**
     * C7 — Concurrent recordSpend on the same session accumulates atomically.
     *
     * <p>Launches 50 virtual threads that each record $0.01 on the same sessionId. After
     * all complete, the active window's cumulative must equal exactly $0.50 (50 × $0.01).
     * Lost updates (broken synchronization) would manifest as a sub-total <$0.50.
     */
    @Test
    void burnRateMonitor_concurrentRecordSpend_accumulatesWithoutLostUpdates() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String agentId = "agent-conc-" + tag;
        String sessionId = "session-conc-" + tag;
        int threadCount = 50;
        double perThreadSpend = 0.01;

        burnRateMonitor.registerBaseline(agentId, 1000.00); // high baseline → no anomaly noise

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            Thread.ofVirtual().name("conc-recordspend-" + i).start(() -> {
                try {
                    start.await();
                    burnRateMonitor.recordSpend(sessionId, agentId, perThreadSpend);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS),
                "all " + threadCount + " virtual threads must complete recordSpend within 10s");

        double cumulative = burnRateMonitor.getActiveWindows().get(sessionId).getCumulativeUsd();
        assertEquals(threadCount * perThreadSpend, cumulative, 0.0001,
                "cumulativeUsd must equal exact threadCount × perThreadSpend — any deficit indicates "
                        + "lost updates from broken synchronization. got=" + cumulative);

        burnRateMonitor.evictSession(sessionId);
    }

    /**
     * C8a — Missing rate row → DEFAULT_RATE applied (not silent $0).
     *
     * <p>Caller passes a model_id with NO corresponding row in {@code finops_valuation_rate}.
     * {@code LiveValuationEngine.resolveRate} must fall back to {@code DEFAULT_RATE}
     * ($1/k input + $2/k output) — the conservative pin that prevents silent $0 financial
     * accounting. 1000 input + 500 output → $1.00 + $1.00 = $2.00.
     */
    @Test
    void liveValuationEngine_unknownModel_fallsBackToDefaultRate_notSilentZero() {
        String unknownModel = "completely-unknown-model-" + UUID.randomUUID();

        double cost = valuationEngine.calculateUsd(unknownModel, 1000L, 500L);

        assertEquals(2.00, cost, 0.0001,
                "unknown model must use DEFAULT_RATE ($1/k input + $2/k output). "
                        + "1000 input × $1/k + 500 output × $2/k = $2.00. got=" + cost);
    }

    /**
     * C8b — Explicit zero-rate model → $0 cost, no exception.
     *
     * <p>Seed a rate row with both rates = 0.00 (a "free tier" model). Asserts the engine
     * computes $0 cost without throwing. This matters: a budget ceiling can still be applied
     * to zero-cost runs (the ceiling check happens after cost computation), and zero cost
     * must not be confused with "no rate row" (which gets the conservative DEFAULT_RATE).
     */
    @Test
    void liveValuationEngine_zeroRateModel_computesZeroCostWithoutThrowing() {
        String freeModel = "free-tier-model-" + UUID.randomUUID().toString().substring(0, 8);
        valuationEngine.register(new ModelValuationRate(freeModel, 0.00, 0.00));

        double cost = valuationEngine.calculateUsd(freeModel, 1000L, 500L);

        assertEquals(0.00, cost, 0.0001,
                "zero-rate model must compute $0 cost; got=" + cost);
    }
}
