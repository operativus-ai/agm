package com.operativus.agentmanager.core.model;

import java.util.List;
import java.util.Map;
import com.operativus.agentmanager.core.model.enums.RunStatus;

/**
 * Domain Responsibility: Represents the synchronous API response payload containing the final output and execution metadata for a fully completed agent run. Fulfills Requirement 3.1.2 A.
 * State: Stateless (Immutable Record carrier)
 *
 * <p>The {@code metrics} field exposes inline per-run telemetry (gap #19,
 * {@link RunMetrics}). Populated by {@code AgentService.buildMetrics(...)}.
 */
public record RunResponse(
    String runId,
    String sessionId,
    String content,              // The final Markdown response
    Map<String, Object> metadata,// Usage stats, model info
    List<ToolCallDTO> tools,     // Audit log of tools called
    List<String> reasoningSteps, // Captured "Inner Thoughts"
    RunStatus status,            // "COMPLETED", "FAILED"
    RunMetrics metrics           // Inline per-run telemetry — gap #19
) {}
