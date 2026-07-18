package com.operativus.agentmanager.core.model;

/**
 * Domain Responsibility: Acts as an immutable data transfer object to carry internal telemetry regarding agent execution quality.
 * State: Stateless (Immutable Record carrier)
 */
public record DeveloperMetricsDTO(
    double testabilityScore,
    String maintainabilityGrade,
    long evaluationCount
) {}
