package com.operativus.agentmanager.core.model;

public record ThreatEventDTO(
    String id,
    String timestamp,
    String agentId,
    String threatLevel,
    String type,
    String target,
    String status
) {}
