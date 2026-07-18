package com.operativus.agentmanager.core.exception;

public class SwarmHandOffException extends RuntimeException {
    private final String targetAgentId;
    private final String handOffContext;

    public SwarmHandOffException(String targetAgentId, String handOffContext) {
        super("Agent requested a swarm handoff to " + targetAgentId);
        this.targetAgentId = targetAgentId;
        this.handOffContext = handOffContext;
    }

    public String getTargetAgentId() {
        return targetAgentId;
    }

    public String getHandOffContext() {
        return handOffContext;
    }
}
