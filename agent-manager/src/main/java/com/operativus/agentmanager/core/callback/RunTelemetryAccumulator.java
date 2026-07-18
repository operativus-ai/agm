package com.operativus.agentmanager.core.callback;

import com.operativus.agentmanager.core.entity.AgentRun;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * Domain Responsibility: Per-run accumulator for LLM-call telemetry (tokens, cost, errors, safety).
 *     Bound per-run as a ScopedValue in {@link AgentContextHolder#telemetry}; advisors accumulate
 *     into it during each LLM call, {@code AgentRunFinalizer} reads and persists once at run exit.
 * State: Stateful, thread-safe (lock-free counters for swarm parallel execution — Risk R-13).
 *
 * <p>Cost is stored as integer micro-USD ({@code long} via {@link LongAdder}) to avoid double
 * race conditions under concurrent writes from parallel swarm branches. Plain {@code long}/{@code double}
 * fields with {@code +=} would lose updates; {@link java.util.concurrent.atomic.AtomicLong} has worse
 * throughput under high write contention than {@link LongAdder}.
 *
 * <p>Non-counter scalars ({@code model}, {@code errorType}, {@code errorMessage},
 * {@code orchestrationStrategy}) are {@code volatile} — last-write-wins semantics are acceptable
 * since each LLM call for a given run uses the same model and only one error terminates the run.
 */
public final class RunTelemetryAccumulator {

    private static final long MICROS_PER_USD = 1_000_000L;

    private final LongAdder totalInputTokens = new LongAdder();
    private final LongAdder totalOutputTokens = new LongAdder();
    private final LongAdder totalReasoningTokens = new LongAdder();
    private final LongAdder totalCostMicroUsd = new LongAdder();
    private final AtomicInteger llmCallCount = new AtomicInteger(0);
    private final AtomicInteger errorCount = new AtomicInteger(0);
    private final AtomicReference<Double> safetyRiskScore = new AtomicReference<>();

    private volatile String model;
    private volatile String errorType;
    private volatile String errorMessage;
    private volatile String orchestrationStrategy;

    private final long startedAtEpochMillis = Instant.now().toEpochMilli();

    public void addInputTokens(long tokens) {
        if (tokens > 0) totalInputTokens.add(tokens);
    }

    public void addOutputTokens(long tokens) {
        if (tokens > 0) totalOutputTokens.add(tokens);
    }

    public void addReasoningTokens(long tokens) {
        if (tokens > 0) totalReasoningTokens.add(tokens);
    }

    public void addCostUsd(double usd) {
        if (usd > 0) totalCostMicroUsd.add(Math.round(usd * MICROS_PER_USD));
    }

    public void incrementLlmCalls() {
        llmCallCount.incrementAndGet();
    }

    public void incrementErrors() {
        errorCount.incrementAndGet();
    }

    public void recordError(String type, String message) {
        this.errorType = type;
        this.errorMessage = message;
        errorCount.incrementAndGet();
    }

    public void setModelIfAbsent(String modelName) {
        if (modelName != null && this.model == null) this.model = modelName;
    }

    public void setOrchestrationStrategy(String strategy) {
        this.orchestrationStrategy = strategy;
    }

    /** Last-write-wins for observed safety risk; advisors may overwrite with a new score. */
    public void updateSafetyRiskScore(Double score) {
        if (score == null) return;
        safetyRiskScore.set(score);
    }

    public long getTotalInputTokens() {
        return totalInputTokens.sum();
    }

    public long getTotalOutputTokens() {
        return totalOutputTokens.sum();
    }

    public long getTotalReasoningTokens() {
        return totalReasoningTokens.sum();
    }

    public double getTotalCostUsd() {
        return totalCostMicroUsd.sum() / (double) MICROS_PER_USD;
    }

    public int getLlmCallCount() {
        return llmCallCount.get();
    }

    public int getErrorCount() {
        return errorCount.get();
    }

    public Double getSafetyRiskScore() {
        return safetyRiskScore.get();
    }

    public String getModel() {
        return model;
    }

    public String getErrorType() {
        return errorType;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getOrchestrationStrategy() {
        return orchestrationStrategy;
    }

    public long getStartedAtEpochMillis() {
        return startedAtEpochMillis;
    }

    public long elapsedMillis() {
        return Instant.now().toEpochMilli() - startedAtEpochMillis;
    }

    /**
     * Copies accumulated telemetry onto the given AgentRun. Zero-valued counters are written as
     * null so the DB distinguishes "no telemetry captured" from "zero usage". Called exclusively
     * from {@code AgentRunFinalizer} after reloading a fresh AgentRun instance.
     */
    public void applyTo(AgentRun run) {
        if (run == null) return;

        long input = getTotalInputTokens();
        long output = getTotalOutputTokens();
        long reasoning = getTotalReasoningTokens();

        if (input > 0) run.setInputTokens(input);
        if (output > 0) run.setOutputTokens(output);
        if (reasoning > 0) run.setReasoningTokens(reasoning);

        long cost = totalCostMicroUsd.sum();
        if (cost > 0) {
            run.setTotalCostUsd(BigDecimal.valueOf(cost, 6).setScale(6, RoundingMode.HALF_UP));
        }

        if (model != null) run.setModel(model);
        if (errorType != null) run.setErrorType(errorType);
        if (errorMessage != null) run.setErrorMessage(truncate(errorMessage, 4000));
        if (orchestrationStrategy != null) run.setOrchestrationStrategy(orchestrationStrategy);

        Double risk = safetyRiskScore.get();
        if (risk != null) run.setSafetyRiskScore(BigDecimal.valueOf(risk).setScale(3, RoundingMode.HALF_UP));

        run.setDurationMs(elapsedMillis());
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max);
    }
}
