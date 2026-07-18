package com.operativus.agentmanager.core.model;

public record ValidationResponseDTO(
    boolean success,
    String message,
    Integer latencyMs
) {}
