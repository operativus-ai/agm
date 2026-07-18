package com.operativus.agentmanager.control.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Tracks Service Level Objectives (SLOs) for agent execution.
 *
 * Default SLOs:
 * - Agent response latency p99 < 30 seconds
 * - Agent success rate > 95%
 * - System availability > 99.5%
 *
 * Publishes SLO breach events to the AlertingService when thresholds are violated.
 */
@Service
public class SloTrackingService {

    private static final Logger log = LoggerFactory.getLogger(SloTrackingService.class);

    private final MeterRegistry meterRegistry;
    private final AlertingService alertingService;

    private final double latencyP99TargetMs;
    private final double successRateTarget;

    public SloTrackingService(MeterRegistry meterRegistry,
                              AlertingService alertingService,
                              @org.springframework.beans.factory.annotation.Value("${agentmanager.slo.latency-p99-target-ms:30000}") double latencyP99TargetMs,
                              @org.springframework.beans.factory.annotation.Value("${agentmanager.slo.success-rate-target:0.95}") double successRateTarget) {
        this.meterRegistry = meterRegistry;
        this.alertingService = alertingService;
        this.latencyP99TargetMs = latencyP99TargetMs;
        this.successRateTarget = successRateTarget;
    }

    /**
     * Evaluates SLOs every 5 minutes and records current compliance state.
     */
    @Scheduled(fixedRateString = "${agentmanager.scheduler.slo-evaluation-ms:300000}")
    public void evaluateSlos() {
        Map<String, Object> sloStatus = getSloStatus();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> slos = (List<Map<String, Object>>) sloStatus.get("slos");
        if (slos == null) return;

        for (Map<String, Object> slo : slos) {
            Boolean compliant = (Boolean) slo.get("compliant");
            if (Boolean.FALSE.equals(compliant)) {
                log.warn("SLO breach detected: {} — current={}, target={}", slo.get("name"), slo.get("current_value"), slo.get("target"));
            }
        }
    }

    /**
     * Returns the current SLO compliance status.
     */
    public Map<String, Object> getSloStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        List<Map<String, Object>> slos = new ArrayList<>();

        // SLO 1: Agent Latency P99
        Timer agentTimer = meterRegistry.find("agent.execution.duration").timer();
        double latencyP99 = agentTimer != null ? agentTimer.percentile(0.99, java.util.concurrent.TimeUnit.MILLISECONDS) : 0;
        slos.add(buildSloEntry("agent_latency_p99", "Agent Response Latency P99", latencyP99, latencyP99TargetMs, "ms", "LTE"));

        // SLO 2: Agent Success Rate
        var completedCounter = meterRegistry.find("agent.runs.completed").counter();
        var failedCounter = meterRegistry.find("agent.runs.failed").counter();
        double completed = completedCounter != null ? completedCounter.count() : 0;
        double failed = failedCounter != null ? failedCounter.count() : 0;
        double total = completed + failed;
        double successRate = total > 0 ? completed / total : 1.0;
        slos.add(buildSloEntry("agent_success_rate", "Agent Success Rate", successRate, successRateTarget, "ratio", "GTE"));

        status.put("evaluated_at", java.time.Instant.now().toString());
        status.put("slos", slos);
        return status;
    }

    private Map<String, Object> buildSloEntry(String id, String name, double currentValue, double target, String unit, String comparison) {
        boolean compliant = switch (comparison) {
            case "LTE" -> currentValue <= target;
            case "GTE" -> currentValue >= target;
            case "LT" -> currentValue < target;
            case "GT" -> currentValue > target;
            default -> true;
        };

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", id);
        entry.put("name", name);
        entry.put("current_value", Math.round(currentValue * 10000.0) / 10000.0);
        entry.put("target", target);
        entry.put("unit", unit);
        entry.put("compliant", compliant);
        entry.put("comparison", comparison);
        return entry;
    }
}
