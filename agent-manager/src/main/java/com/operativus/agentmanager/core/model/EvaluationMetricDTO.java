package com.operativus.agentmanager.core.model;

public record EvaluationMetricDTO(
    String target,
    double score,
    String metric,
    String drift
) {}
