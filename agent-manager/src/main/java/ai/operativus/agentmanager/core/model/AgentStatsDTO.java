package ai.operativus.agentmanager.core.model;

import java.time.LocalDateTime;

/**
 * Domain Responsibility: Acts as an immutable data transfer object carrying aggregated statistical data about an agent's execution history.
 * State: Stateless (Immutable Record carrier)
 */
public record AgentStatsDTO(
    String agentId,
    String agentName,
    long totalRuns,
    long activeRuns,
    long errorRuns,
    LocalDateTime lastRunAt
) {}
