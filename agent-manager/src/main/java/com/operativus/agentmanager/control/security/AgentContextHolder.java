package com.operativus.agentmanager.control.security;

import com.operativus.agentmanager.core.model.TeamManifest.AgentManifest;

/**
 * Manages the "On-Behalf-Of" (OBO) execution context and 
 * bounded autonomy profile for active Agent Virtual Threads.
 */
public class AgentContextHolder {

    public static final ScopedValue<AgentContext> CONTEXT = ScopedValue.newInstance();

    public static AgentContext getContext() {
        if (!CONTEXT.isBound()) {
            throw new org.springframework.security.authentication.AuthenticationCredentialsNotFoundException("No AgentContext bound to the current virtual thread");
        }
        return CONTEXT.get();
    }

    /**
     * The isolated identity sandbox bounds for the active inference operation.
     */
    public record AgentContext(
        String teamId,
        String humanInitiatorId, 
        Double remainingBudget,
        AgentManifest agentManifest
    ) {}
}
