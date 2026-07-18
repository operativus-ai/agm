package com.operativus.agentmanager.core.model;

public record SpotBatchJobDTO(
    String id,
    String job,
    String status,
    int progress,
    double cost,
    String compute
) {}
