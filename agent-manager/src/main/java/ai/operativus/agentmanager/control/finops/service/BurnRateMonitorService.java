package ai.operativus.agentmanager.control.finops.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Domain Responsibility: Real-time anomaly detection for AI token spend velocity.
 * Monitors the USD burn rate per agent session over a rolling window and triggers
 * heuristic evaluation when unexpected acceleration is detected.
 *
 * 2026+ Requirement (Gap 4.2): If an agent historically consumes $1.00/hour but a
 * misfire causes $50.00/hour consumption, administrators need real-time anomaly alerts
 * before the absolute monthly budget is exhausted.
 *
 * Detection Mechanism:
 * - Maintains a sliding observation window (default: 60 seconds) per session.
 * - Computes instantaneous burn rate in USD/hour.
 * - Compares against the agent's registered baseline burn rate.
 * - Triggers a warning log and Micrometer alert gauge when the multiplier exceeds the threshold.
 *
 * Architecture Enforcement:
 * - Virtual Thread Supremacy: No reactive streams.
 * - Constructor Injection.
 * - Functional Interface IoC: alert callbacks are injected as functional interfaces, not event publishers.
 *
 * State: Stateful (sliding window accumulators per session)
 */
@Service
public class BurnRateMonitorService {

    private static final Logger log = LoggerFactory.getLogger(BurnRateMonitorService.class);

    /** Rolling observation window for burn rate computation (60 seconds). */
    private static final long WINDOW_SECONDS = 60L;

    /** Anomaly threshold multiplier: alert if burn rate exceeds baseline by this factor. */
    private final double anomalyMultiplierThreshold;

    /** Default baseline burn rate (USD/hour) for agents with no registered history. */
    private final double defaultBaselineUsdPerHour;

    private static final String METRIC_BURN_RATE     = "finops.agent.burn_rate.usd_per_hour";
    private static final String METRIC_ANOMALY_SCORE = "finops.agent.burn_rate.anomaly_score";

    private final MeterRegistry meterRegistry;

    /**
     * Tracks cumulative spend within the current observation window per session.
     * Key: sessionId, Value: sliding window accumulator.
     */
    private final ConcurrentHashMap<String, WindowAccumulator> windowAccumulators =
        new ConcurrentHashMap<>();

    /**
     * Registered baseline burn rates per agent (USD/hour), populated from historical data.
     * Key: agentId, Value: baseline USD/hour.
     */
    private final ConcurrentHashMap<String, Double> agentBaselineRates = new ConcurrentHashMap<>();

    /** Maps sessionId → agentId for anomaly reporting. Populated on first spend record. */
    private final ConcurrentHashMap<String, String> sessionAgentMap = new ConcurrentHashMap<>();

    /**
     * Optional alert callback for anomaly notifications (Slack, PagerDuty, etc.).
     * Injected as a functional interface — no ApplicationEventPublisher dependency.
     */
    private final AnomalyAlertCallback alertCallback;

    @org.springframework.beans.factory.annotation.Autowired
    public BurnRateMonitorService(MeterRegistry meterRegistry,
                                  @org.springframework.beans.factory.annotation.Value("${agentmanager.finops.anomaly-multiplier:5.0}") double anomalyMultiplierThreshold,
                                  @org.springframework.beans.factory.annotation.Value("${agentmanager.finops.default-baseline-usd-per-hour:1.00}") double defaultBaselineUsdPerHour) {
        this(meterRegistry, anomalyMultiplierThreshold, defaultBaselineUsdPerHour,
            (sessionId, agentId, burnRateUsdPerHour, baselineUsdPerHr) ->
                log.warn("FINOPS ANOMALY ALERT — session={}, agent={}, burnRate=${}/hr vs baseline=${}/hr ({}x). " +
                         "Immediate investigation recommended.",
                    sessionId, agentId,
                    String.format("%.2f", burnRateUsdPerHour),
                    String.format("%.2f", baselineUsdPerHr),
                    String.format("%.1f", burnRateUsdPerHour / Math.max(baselineUsdPerHr, 0.001))));
    }

    public BurnRateMonitorService(MeterRegistry meterRegistry, double anomalyMultiplierThreshold,
                                  double defaultBaselineUsdPerHour, AnomalyAlertCallback alertCallback) {
        this.meterRegistry   = meterRegistry;
        this.anomalyMultiplierThreshold = anomalyMultiplierThreshold;
        this.defaultBaselineUsdPerHour = defaultBaselineUsdPerHour;
        this.alertCallback   = alertCallback;
    }

    /**
     * @summary Records a USD spend event for a session and evaluates burn rate anomaly heuristics.
     * @logic
     * 1. Accumulates the spend amount into the session's sliding window accumulator.
     * 2. Evicts expired windows (older than WINDOW_SECONDS) and resets the window if stale.
     * 3. Computes the instantaneous burn rate in USD/hour from the current window.
     * 4. Publishes burn rate and anomaly score as Micrometer gauges for observability dashboards.
     * 5. If the burn rate exceeds anomalyMultiplierThreshold × baseline, triggers the alert callback.
     *
     * @param sessionId  Session identifier for the agent execution.
     * @param agentId    Agent identifier for baseline lookup and alert context.
     * @param spendUsd   USD amount spent in this observation (single inference call cost).
     */
    public void recordSpend(String sessionId, String agentId, double spendUsd) {
        if (sessionId == null || spendUsd <= 0.0) return;

        sessionAgentMap.putIfAbsent(sessionId, agentId != null ? agentId : "unknown");

        WindowAccumulator window = windowAccumulators.computeIfAbsent(
            sessionId, k -> {
                publishMetrics(sessionId, agentId);
                return new WindowAccumulator(Instant.now());
            });

        window.accumulate(spendUsd);

        double burnRateUsdPerHour = window.computeBurnRatePerHour(WINDOW_SECONDS);
        double baseline = agentBaselineRates.getOrDefault(
            agentId != null ? agentId : "unknown", defaultBaselineUsdPerHour);

        double anomalyScore = burnRateUsdPerHour / Math.max(baseline, 0.001);

        if (anomalyScore >= anomalyMultiplierThreshold) {
            log.warn("Burn rate anomaly detected — session={}, agent={}, rate=${}/hr, " +
                     "baseline=${}/hr, multiplier={}x",
                sessionId, agentId,
                String.format("%.4f", burnRateUsdPerHour),
                String.format("%.4f", baseline),
                String.format("%.1f", anomalyScore));
            alertCallback.onAnomaly(sessionId, agentId, burnRateUsdPerHour, baseline);
        }
    }

    /**
     * @summary Registers or updates the historical baseline burn rate for an agent.
     * @logic Used by administrators or a background job that computes rolling averages
     *        from historical session data. Enables accurate anomaly threshold comparison.
     *
     * @param agentId          Agent identifier.
     * @param baselineUsdPerHour Expected normal USD/hour consumption for this agent.
     */
    public void registerBaseline(String agentId, double baselineUsdPerHour) {
        agentBaselineRates.put(agentId, baselineUsdPerHour);
        log.debug("Registered burn rate baseline for agent [{}]: ${}/hr",
                agentId, String.format("%.4f", baselineUsdPerHour));
    }

    /**
     * @summary Returns a snapshot of all active session window accumulators for diagnostics.
     */
    public Map<String, WindowAccumulator> getActiveWindows() {
        return Map.copyOf(windowAccumulators);
    }

    /**
     * @summary Returns all sessions currently exceeding the anomaly threshold.
     * Each entry contains sessionId, agentId, current burn rate, baseline, and ratio.
     */
    public java.util.List<AnomalyEntry> getActiveAnomalies() {
        return windowAccumulators.entrySet().stream()
            .map(e -> {
                String sessionId = e.getKey();
                String agentId = sessionAgentMap.getOrDefault(sessionId, "unknown");
                double burnRate = e.getValue().computeBurnRatePerHour(WINDOW_SECONDS);
                double baseline = agentBaselineRates.getOrDefault(agentId, defaultBaselineUsdPerHour);
                double ratio = burnRate / Math.max(baseline, 0.001);
                return new AnomalyEntry(sessionId, agentId, burnRate, baseline, ratio);
            })
            .filter(a -> a.anomalyRatio() >= anomalyMultiplierThreshold)
            .toList();
    }

    /** Immutable anomaly data transfer object for use by the REST controller. */
    public record AnomalyEntry(
        String sessionId, String agentId,
        double burnRateUsdPerHour, double baselineUsdPerHour, double anomalyRatio
    ) {}

    /**
     * @summary Evicts the observation window for a completed session to release memory.
     */
    public void evictSession(String sessionId) {
        windowAccumulators.remove(sessionId);
        sessionAgentMap.remove(sessionId);
        
        // Remove gauges to prevent Micrometer registry memory leaks
        meterRegistry.find(METRIC_BURN_RATE).tag("session_id", sessionId).meters().forEach(meterRegistry::remove);
        meterRegistry.find(METRIC_ANOMALY_SCORE).tag("session_id", sessionId).meters().forEach(meterRegistry::remove);
        
        log.debug("Evicted burn rate window and metrics for completed session [{}].", sessionId);
    }

    /**
     * @summary Clears all in-memory burn-rate state (window accumulators, agent baselines,
     *   session→agent map). The accumulators live in-process and survive a DB truncate, so
     *   integration tests that assert the empty-DB shape of {@code /finops/burn-rates/active}
     *   and {@code /finops/anomalies/active} must reset this monitor between tests — otherwise
     *   accumulators left by a prior test sharing the cached Spring context bleed through.
     */
    public void reset() {
        windowAccumulators.clear();
        agentBaselineRates.clear();
        sessionAgentMap.clear();
    }

    /**
     * @summary Publishes burn rate and anomaly score as Micrometer gauge metrics.
     */
    private void publishMetrics(String sessionId, String agentId) {
        String effectiveAgentId = agentId != null ? agentId : "unknown";

        Gauge.builder(METRIC_BURN_RATE, this,
                self -> self.windowAccumulators.containsKey(sessionId)
                    ? self.windowAccumulators.get(sessionId).computeBurnRatePerHour(WINDOW_SECONDS)
                    : 0.0)
            .tag("session_id", sessionId)
            .tag("agent_id",   effectiveAgentId)
            .description("Real-time USD/hour burn rate for this agent session")
            .register(meterRegistry);

        Gauge.builder(METRIC_ANOMALY_SCORE, this,
                self -> self.windowAccumulators.containsKey(sessionId)
                    ? self.windowAccumulators.get(sessionId).computeBurnRatePerHour(WINDOW_SECONDS)
                        / Math.max(self.agentBaselineRates.getOrDefault(
                            effectiveAgentId, defaultBaselineUsdPerHour), 0.001)
                    : 0.0)
            .tag("session_id", sessionId)
            .tag("agent_id",   effectiveAgentId)
            .description("Burn rate anomaly multiplier vs registered agent baseline")
            .register(meterRegistry);
    }

    // -------------------------------------------------------------------------
    // Functional interface for anomaly alert callbacks (Slack, PagerDuty, etc.)
    // -------------------------------------------------------------------------

    /**
     * Functional interface for FinOps anomaly alert delivery.
     * Allows injection of Slack/PagerDuty/email implementations without coupling
     * to a specific notification framework or ApplicationEventPublisher.
     */
    @FunctionalInterface
    public interface AnomalyAlertCallback {
        void onAnomaly(String sessionId, String agentId, double burnRateUsdPerHour, double baselineUsdPerHour);
    }

    // -------------------------------------------------------------------------
    // Sliding window accumulator
    // -------------------------------------------------------------------------

    /**
     * Accumulates USD spend within a rolling observation window.
     * Resets the window when it exceeds the configured WINDOW_SECONDS duration.
     * Thread-safe via synchronized methods (called from virtual threads under low contention).
     */
    public static final class WindowAccumulator {

        private Instant windowStart;
        private double cumulativeUsd;

        WindowAccumulator(Instant windowStart) {
            this.windowStart  = windowStart;
            this.cumulativeUsd = 0.0;
        }

        synchronized void accumulate(double usd) {
            Instant now = Instant.now();
            long elapsedSeconds = now.getEpochSecond() - windowStart.getEpochSecond();

            if (elapsedSeconds > WINDOW_SECONDS) {
                // Reset window when it expires — start fresh for the next interval
                windowStart    = now;
                cumulativeUsd = usd;
            } else {
                cumulativeUsd += usd;
            }
        }

        synchronized double computeBurnRatePerHour(long windowSeconds) {
            Instant now = Instant.now();
            long elapsedSeconds = Math.max(1L,
                now.getEpochSecond() - windowStart.getEpochSecond());

            double elapsedHours = elapsedSeconds / 3600.0;
            return cumulativeUsd / Math.max(elapsedHours, 1.0 / 3600.0);
        }

        public synchronized double getCumulativeUsd() {
            return cumulativeUsd;
        }
    }
}
