package ai.operativus.agentmanager.core.model;

/**
 * Domain Responsibility: Inline per-run telemetry exposed on the synchronous
 *   {@link RunResponse#metrics()} field. Closes gap #19 from
 *   {@code docs/analysis/agm-missing-features.md}.
 * State: Stateless (Immutable Record carrier).
 *
 * <p><b>Null-vs-zero semantics:</b> counter fields are boxed ({@link Long} /
 * {@link Integer}) so {@code null} = "not captured by any advisor on this run"
 * and a non-null value = "captured." Today, only {@code inputTokens},
 * {@code outputTokens}, {@code llmCallCount}, and {@code errorCount} are
 * captured by any production advisor (per the V-cost verdict on PR introducing
 * this class). {@code reasoningTokens} stays in the contract as a boxed-null
 * placeholder so a future advisor wiring does not require a DTO change.
 *
 * <p><b>What is intentionally NOT here:</b> {@code totalCostUsd} was dropped
 * from this spec because no advisor calls
 * {@link ai.operativus.agentmanager.core.callback.RunTelemetryAccumulator#addCostUsd(double)}
 * on origin/main today (V-cost verdict RED for cost). A separate spec wires
 * cost capture and adds the field back.
 *
 * <p><b>Construction:</b> only via {@code AgentService.buildMetrics(...)} —
 * this DTO does not import
 * {@code ai.operativus.agentmanager.core.callback.RunTelemetryAccumulator}
 * to preserve {@code core.model} dependency-purity (rule A17).
 */
public record RunMetrics(
        Long inputTokens,
        Long outputTokens,
        Long reasoningTokens,
        Integer llmCallCount,
        Integer errorCount,
        long durationMs,
        String model,
        String errorType,
        String errorMessage
) {
}
