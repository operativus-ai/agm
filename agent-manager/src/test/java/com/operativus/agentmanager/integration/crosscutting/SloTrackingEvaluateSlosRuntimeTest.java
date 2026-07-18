package com.operativus.agentmanager.integration.crosscutting;

import com.operativus.agentmanager.control.service.SloTrackingService;
import com.operativus.agentmanager.integration.BaseIntegrationTest;
import com.operativus.agentmanager.integration.support.FakeChatModelConfig;
import com.operativus.agentmanager.integration.support.FakeEmbeddingModelConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain Responsibility: Behavior pin for {@link SloTrackingService#evaluateSlos()} — the
 *   {@code @Scheduled} method previously covered only by the no-throw smoke pin in
 *   {@link ScheduledTasksRuntimeTest}. The existing {@code ObservabilityRuntimeTest} §20.9
 *   pins the SHAPE of {@code getSloStatus()} (2 SLOs, units, comparison ops) under the
 *   empty-meter fallback (successRate=1.0, latencyP99=0 → both vacuously compliant). This
 *   class fills the missing half: the THRESHOLD-COMPARISON branches that flip a SLO from
 *   compliant=true → false when meters carry non-compliant data.
 *
 *   <p>Locked invariants:
 *   <ol>
 *     <li>evaluateSlos() does not throw when meters carry seeded data (smoke pin re-asserted
 *         under non-trivial state)</li>
 *     <li>Latency p99 > target → agent_latency_p99 SLO non-compliant (LTE comparison branch)</li>
 *     <li>Failure-majority counters → agent_success_rate SLO non-compliant (GTE branch)</li>
 *     <li>Success rate exactly at the configured target → compliant (GTE boundary is inclusive)</li>
 *     <li>evaluateSlos() is idempotent — two calls yield the same compliance verdict on
 *         unchanged meter state</li>
 *   </ol>
 * State: Stateful — seeds shared {@link MeterRegistry} meters. {@code @AfterEach} removes
 *   exactly the three meters this test touches ({@code agent.execution.duration},
 *   {@code agent.runs.completed}, {@code agent.runs.failed}) so the JVM-scoped registry
 *   does not leak seeded data into subsequent tests in the same context.
 *
 * <p>Why direct meter seeding rather than driving real agent runs: the production producers
 *   for these three meters are not wired (see ObservabilityRuntimeTest §20.9 GAP comment —
 *   {@code agent.execution.duration}, {@code agent.runs.completed}, {@code agent.runs.failed}
 *   are orphan readers). Seeding the registry directly is the only way to pin the threshold
 *   logic until those producers land; once they do, this fixture should still work because
 *   the seeded values are additive to whatever the producers contribute on a fresh test.
 */
@Import({
        FakeChatModelConfig.class,
        FakeEmbeddingModelConfig.class
})
public class SloTrackingEvaluateSlosRuntimeTest extends BaseIntegrationTest {

    private static final String METER_LATENCY = "agent.execution.duration";
    private static final String METER_COMPLETED = "agent.runs.completed";
    private static final String METER_FAILED = "agent.runs.failed";

    private static final double LATENCY_TARGET_MS = 30_000d;
    private static final double SUCCESS_RATE_TARGET = 0.95d;

    @Autowired private SloTrackingService sloTrackingService;
    @Autowired private MeterRegistry meterRegistry;

    @AfterEach
    void removeSeededMeters() {
        // Strip exactly the meters this test class seeded so the global JVM-scoped registry
        // does not carry forward into other tests in the same Spring context. Using
        // meterRegistry.clear() would also remove meters owned by other beans; per-name
        // removal is surgical.
        removeMetersByName(METER_LATENCY);
        removeMetersByName(METER_COMPLETED);
        removeMetersByName(METER_FAILED);
    }

    // §27.4 behavior — evaluateSlos() does not throw when the meter registry carries seeded,
    // non-trivial data. The smoke pin only asserts empty-state survival; this pins runtime
    // robustness with both compliant and non-compliant SLO state present.
    @Test
    void evaluateSlosWithSeededMetersDoesNotThrow() {
        seedSuccessRatio(95, 5); // 95% success — compliant
        seedLatencySamplesMs(100, 200, 500); // well under 30s — compliant

        assertDoesNotThrow(() -> sloTrackingService.evaluateSlos(),
                "evaluateSlos must not throw when meters carry seeded compliant data");
    }

    // §27.4 behavior — when the latency timer records samples whose p99 exceeds
    // latencyP99TargetMs (30s default), the agent_latency_p99 SLO flips to non-compliant.
    // Pins the LTE comparison branch of buildSloEntry.
    //
    // Note: Micrometer's basic Timer.percentile(0.99) returns NaN unless the Timer is built
    // with publishPercentiles(...) at registration time (HDR histogram backing). Seeding
    // via meterRegistry.timer(name) yields a basic timer whose percentile would be NaN →
    // rounded to 0.0 → vacuously compliant (defeating the pin). Build the timer explicitly
    // so the p99 reader returns a real value. This also pins the assumption that the
    // (currently-orphan) production producer MUST register `agent.execution.duration` with
    // publishPercentiles(0.99) — otherwise the SLO is silently always-compliant.
    @Test
    void evaluateSlosWithLatencyP99AboveTargetMarksLatencySloNonCompliant() {
        Timer timer = Timer.builder(METER_LATENCY)
                .publishPercentiles(0.99)
                .register(meterRegistry);
        // 100 samples all at 60s → p99 unambiguously ~60_000ms, well above the 30_000ms target
        for (int i = 0; i < 100; i++) {
            timer.record(Duration.ofSeconds(60));
        }

        sloTrackingService.evaluateSlos();
        Map<String, Object> latency = findSlo("agent_latency_p99");

        assertEquals(false, latency.get("compliant"),
                "latency SLO must be non-compliant when p99 exceeds the target; "
                        + "current_value=" + latency.get("current_value") + ", target=" + latency.get("target"));
        assertTrue(((Number) latency.get("current_value")).doubleValue() > LATENCY_TARGET_MS,
                "current_value must reflect the seeded p99 > target; "
                        + "got " + latency.get("current_value"));
    }

    // §27.4 behavior — when failures dominate completions, the agent_success_rate SLO flips
    // to non-compliant. Pins the GTE comparison branch + the rate computation
    // (completed / (completed + failed)).
    @Test
    void evaluateSlosWithFailureMajorityMarksSuccessRateNonCompliant() {
        seedSuccessRatio(10, 90); // 10/100 = 0.10 success rate, far below 0.95

        sloTrackingService.evaluateSlos();
        Map<String, Object> successRate = findSlo("agent_success_rate");

        assertEquals(false, successRate.get("compliant"),
                "success-rate SLO must be non-compliant when 10/100 runs succeed; "
                        + "current_value=" + successRate.get("current_value") + ", target=" + successRate.get("target"));
        double actual = ((Number) successRate.get("current_value")).doubleValue();
        assertTrue(actual >= 0.09 && actual <= 0.11,
                "current_value must reflect ~0.10 success rate from 10 completed / 100 total; "
                        + "got " + actual);
    }

    // §27.4 behavior — the GTE comparison is INCLUSIVE at the boundary. 95 completed + 5
    // failed = exactly 0.95, which equals the default successRateTarget. This pins the
    // comparison semantics ("GTE" means ≥, not >).
    @Test
    void evaluateSlosAtSuccessRateExactlyEqualToTargetIsCompliant() {
        seedSuccessRatio(95, 5); // exactly 0.95

        sloTrackingService.evaluateSlos();
        Map<String, Object> successRate = findSlo("agent_success_rate");

        double actual = ((Number) successRate.get("current_value")).doubleValue();
        assertEquals(SUCCESS_RATE_TARGET, actual, 0.0001,
                "precondition: seeded ratio must round to exactly the target; "
                        + "got " + actual);
        assertEquals(true, successRate.get("compliant"),
                "GTE comparison must be inclusive — current_value=" + actual
                        + " equals target=" + SUCCESS_RATE_TARGET
                        + " and therefore must be compliant. A failure here means someone changed "
                        + "the comparison semantics from GTE (≥) to GT (>) without updating the "
                        + "buildSloEntry switch.");
    }

    // §27.4 behavior — evaluateSlos is idempotent across repeat calls on unchanged meter
    // state. Pins that the tick does not mutate the SLO computation surface (it only reads
    // meters + logs warnings on breach; no internal counters tick forward per invocation).
    @Test
    void evaluateSlosIsIdempotentAcrossRepeatedInvocation() {
        seedSuccessRatio(80, 20); // 0.80 — non-compliant for both calls
        seedLatencySamplesMs(100, 200, 300); // compliant for both calls

        sloTrackingService.evaluateSlos();
        Map<String, Object> firstSuccess = findSlo("agent_success_rate");
        Map<String, Object> firstLatency = findSlo("agent_latency_p99");

        sloTrackingService.evaluateSlos();
        Map<String, Object> secondSuccess = findSlo("agent_success_rate");
        Map<String, Object> secondLatency = findSlo("agent_latency_p99");

        assertEquals(firstSuccess.get("compliant"), secondSuccess.get("compliant"),
                "success-rate compliance verdict must be stable across repeat invocations");
        assertEquals(firstSuccess.get("current_value"), secondSuccess.get("current_value"),
                "success-rate current_value must not change between invocations on unchanged meter state");
        assertEquals(firstLatency.get("compliant"), secondLatency.get("compliant"),
                "latency compliance verdict must be stable across repeat invocations");
        assertFalse((Boolean) secondSuccess.get("compliant"),
                "on this 0.80 seed, success rate must remain non-compliant on the second invocation");
    }

    // ─── helpers ───

    private void seedSuccessRatio(int completed, int failed) {
        Counter completedCounter = meterRegistry.counter(METER_COMPLETED);
        Counter failedCounter = meterRegistry.counter(METER_FAILED);
        if (completed > 0) completedCounter.increment(completed);
        if (failed > 0) failedCounter.increment(failed);
    }

    private void seedLatencySamplesMs(long... samplesMs) {
        Timer timer = meterRegistry.timer(METER_LATENCY);
        for (long ms : samplesMs) {
            timer.record(Duration.ofMillis(ms));
        }
    }

    private Map<String, Object> findSlo(String id) {
        Map<String, Object> status = sloTrackingService.getSloStatus();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> slos = (List<Map<String, Object>>) status.get("slos");
        assertNotNull(slos, "getSloStatus must include a non-null 'slos' list");
        return slos.stream()
                .filter(s -> id.equals(s.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("SLO id '" + id + "' missing from getSloStatus output"));
    }

    private void removeMetersByName(String name) {
        meterRegistry.find(name).meters().stream()
                .filter(Objects::nonNull)
                .map(Meter::getId)
                .forEach(meterRegistry::remove);
    }
}
