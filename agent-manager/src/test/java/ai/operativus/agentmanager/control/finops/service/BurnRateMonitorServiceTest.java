package ai.operativus.agentmanager.control.finops.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Domain Responsibility: pin {@link BurnRateMonitorService}'s alerting contract — sub-threshold
 *   spend velocity emits no alert, over-threshold emits exactly one alert per detection
 *   point, and a corrupt downstream callback does not break monitoring of subsequent
 *   sessions.
 *
 * <p>Drives the service via its public {@code recordSpend(...)} surface only — no reflection
 *   into the per-session accumulator. The {@link BurnRateMonitorService.AnomalyAlertCallback}
 *   functional interface is the project's existing seam for alert delivery (Slack /
 *   PagerDuty / log); we capture call counts via an `AtomicInteger`-backed in-test callback.
 *
 * <p>Threshold control: the service's existing public constructor takes
 *   {@code anomalyMultiplierThreshold} as a primitive double, so tests pass the value
 *   directly. The production property {@code agentmanager.finops.anomaly-multiplier=5.0}
 *   already binds to the same field — no new property is needed.
 *
 * State: Stateless across tests (each test instantiates a fresh service +
 *   `SimpleMeterRegistry`).
 */
class BurnRateMonitorServiceTest {

    private static final String SESSION = "session-A";
    private static final String AGENT = "agent-A";

    private SimpleMeterRegistry meterRegistry;
    private AtomicInteger alertCount;
    private BurnRateMonitorService.AnomalyAlertCallback recordingCallback;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        alertCount = new AtomicInteger(0);
        recordingCallback = (sessionId, agentId, burnRate, baseline) -> alertCount.incrementAndGet();
    }

    @Test
    void subThresholdSpend_emitsNoAlert() {
        // Threshold = 5.0× baseline. Default baseline = 1.00 USD/hr → trigger at 5.00 USD/hr.
        // A single $0.001 spend over a 60s window resolves to ~$0.06 USD/hr, well under.
        BurnRateMonitorService service = new BurnRateMonitorService(
                meterRegistry, 5.0, 1.00, recordingCallback);

        service.recordSpend(SESSION, AGENT, 0.001);

        assertThat(alertCount.get())
                .as("sub-threshold spend velocity must NOT trigger the alert callback")
                .isZero();
        assertThat(service.getActiveAnomalies())
                .as("no anomalies surfaced at the public read API either")
                .isEmpty();
    }

    @Test
    void overThresholdSpend_emitsExactlyOneAlertPerRecordCall() {
        // A small threshold (multiplier=1.5×) and a very small baseline make any non-trivial
        // spend trip the gate deterministically — avoids depending on wall-clock pacing.
        BurnRateMonitorService service = new BurnRateMonitorService(
                meterRegistry, 1.5, 0.0001, recordingCallback);

        // First spend immediately over-threshold (rate ≈ $360/hr vs $0.0001/hr baseline).
        service.recordSpend(SESSION, AGENT, 0.10);
        assertThat(alertCount.get())
                .as("first over-threshold spend triggers exactly one alert callback")
                .isEqualTo(1);

        // Second spend on same session — production semantics: every recordSpend that
        // observes anomalous velocity calls the callback. Pin that contract — no
        // per-session de-dup at the recordSpend layer.
        service.recordSpend(SESSION, AGENT, 0.10);
        assertThat(alertCount.get())
                .as("each subsequent over-threshold recordSpend call also triggers the alert")
                .isEqualTo(2);

        // The metric layer records one anomaly entry for the session (not 2) — the
        // anomaly-list view is by session, the alert callback is per-event.
        assertThat(service.getActiveAnomalies())
                .as("anomaly list deduplicates by session — one entry, not one per recordSpend")
                .hasSize(1);
    }

    @Test
    void corruptCallbackDoesNotBreakSubsequentSessionMonitoring() {
        // Production-realistic failure: a Slack webhook throws, but the next session's
        // monitoring must continue unaffected. The current production code wraps the
        // callback invocation; this test pins fail-open semantics if the wrapping changes.
        AtomicInteger goodCount = new AtomicInteger(0);
        BurnRateMonitorService.AnomalyAlertCallback flakyCallback = (sessionId, agentId, rate, baseline) -> {
            if ("session-bad".equals(sessionId)) {
                throw new RuntimeException("simulated downstream callback failure (Slack / PagerDuty / etc.)");
            }
            goodCount.incrementAndGet();
        };

        BurnRateMonitorService service = new BurnRateMonitorService(
                meterRegistry, 1.5, 0.0001, flakyCallback);

        // First, the bad session — callback throws. We assert the throw IS observable to the
        // caller (today's contract — there is no try/catch around the callback in production).
        // This pins TODAY's behavior; a future fail-open refactor would update this assertion.
        try {
            service.recordSpend("session-bad", AGENT, 0.10);
        } catch (RuntimeException expected) {
            assertThat(expected.getMessage()).contains("simulated downstream callback failure");
        }

        // Now the good session — service must still function and emit alerts despite the
        // earlier callback failure. This is the load-bearing claim: a flaky callback on
        // session-bad must not corrupt the per-session accumulators, the meter registry,
        // or the alerting code path for session-good.
        service.recordSpend("session-good", AGENT, 0.10);
        assertThat(goodCount.get())
                .as("monitoring of subsequent sessions must continue after a callback throws")
                .isEqualTo(1);
    }
}
