package com.operativus.agentmanager.compute.service;

import com.operativus.agentmanager.core.callback.RunTelemetryAccumulator;
import com.operativus.agentmanager.core.model.RunMetrics;

/**
 * Domain Responsibility: Builds {@link RunMetrics} from
 * {@link RunTelemetryAccumulator}. Lives in {@code compute/service/} to
 * preserve {@code core/model/} dependency-purity (rule A17): the
 * {@link RunMetrics} DTO does not import {@code core/callback/}.
 * State: Stateless utility (private constructor).
 *
 * <p>Two construction paths:
 * <ul>
 *   <li>{@link #fromTelemetry(RunTelemetryAccumulator)} — for code paths where
 *       telemetry SHOULD be bound (the synchronous {@code AgentService.run}
 *       lambda and team-orchestration sync path). Throws
 *       {@link IllegalStateException} on null per rule A19; never silently
 *       returns an empty record for the bound case.</li>
 *   <li>{@link #noTelemetry()} — for code paths that legitimately have no
 *       telemetry bound (e.g., user-cancellation in
 *       {@code AgentService.continueRun} before any LLM call, and the
 *       streaming-team STOP-event contract). Returns an explicit all-null
 *       record. This is NOT a defensive fallback for impossible state — it is
 *       a deliberate construction for paths where the accumulator was never
 *       expected to exist.</li>
 * </ul>
 *
 * <p><b>Null-vs-zero semantics</b> per rule A20: counter fields are returned
 * as boxed {@code null} when the accumulator's underlying counter is {@code 0}
 * (no advisor incremented it on this run). LongAdder semantics make
 * "captured-zero" indistinguishable from "never-incremented" — the chosen
 * mapping treats {@code 0} as "not captured." {@code durationMs} is always
 * populated for the bound case ({@link RunTelemetryAccumulator} records start
 * time on construction); for {@link #noTelemetry()}, it is {@code 0L}.
 */
public final class RunMetricsBuilder {

    private RunMetricsBuilder() {
    }

    /**
     * Build {@link RunMetrics} from a bound accumulator. Throws if the
     * accumulator is null — rule A19, do not paper over impossible state.
     */
    public static RunMetrics fromTelemetry(RunTelemetryAccumulator t) {
        if (t == null) {
            throw new IllegalStateException(
                    "RunTelemetryAccumulator not bound — cannot build RunMetrics. "
                            + "Caller must ensure ScopedValue<AgentContextHolder.telemetry> is bound before constructing RunResponse.");
        }
        long inputT = t.getTotalInputTokens();
        long outputT = t.getTotalOutputTokens();
        long reasoningT = t.getTotalReasoningTokens();
        int llmCalls = t.getLlmCallCount();
        int errors = t.getErrorCount();
        return new RunMetrics(
                inputT > 0 ? inputT : null,
                outputT > 0 ? outputT : null,
                reasoningT > 0 ? reasoningT : null,
                llmCalls > 0 ? llmCalls : null,
                errors > 0 ? errors : null,
                t.elapsedMillis(),
                t.getModel(),
                t.getErrorType(),
                t.getErrorMessage()
        );
    }

    /**
     * Explicit all-null {@link RunMetrics} for code paths that have no
     * telemetry bound. NOT a fallback factory — call sites that use this
     * MUST be paths where telemetry was deliberately not bound (cancellation
     * before LLM execution, streaming-team STOP-event contract).
     */
    public static RunMetrics noTelemetry() {
        return new RunMetrics(null, null, null, null, null, 0L, null, null, null);
    }
}
