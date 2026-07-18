package com.operativus.agentmanager.control.controller.model;

/**
 * Domain Responsibility: Lean projection of AgentEntity for list/summary responses — avoids serializing large configuration blobs.
 * State: Immutable value object
 */
public record AgentSummary(String agentId, String name, String description) {

    public static AgentSummary from(com.operativus.agentmanager.core.entity.AgentEntity agent) {
        return new AgentSummary(agent.getId(), agent.getName(), agent.getDescription());
    }
}
