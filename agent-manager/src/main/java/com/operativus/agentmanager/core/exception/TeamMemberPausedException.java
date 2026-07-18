package com.operativus.agentmanager.core.exception;

import com.operativus.agentmanager.core.model.RequiredAction;

/**
 * Domain Responsibility: Thrown by {@code OrchestrationStrategy} implementations when a
 *     team member's run returned with {@code RunStatus.PAUSED}. Caught by the team branch
 *     of {@code AgentService.run} to lift the child member's HITL state up to the
 *     team-level run record. See Tier 2.5 F2 fix.
 *
 *     Unchecked by design — {@code ScopedValue.where(...).call(Callable)} lambdas only
 *     allow declared {@code Exception} subtypes, and the strategies are wired through
 *     such a Callable in {@code TeamOrchestrationEngine.executeSync}.
 *
 * State: Stateless (Exception carrier).
 */
public class TeamMemberPausedException extends RuntimeException {

    private final String pausedRunId;
    private final String pausedAgentId;
    private final RequiredAction requiredAction;

    public TeamMemberPausedException(String pausedRunId, String pausedAgentId, RequiredAction requiredAction) {
        super("Team member paused: agent='" + pausedAgentId + "' runId='" + pausedRunId + "'");
        this.pausedRunId = pausedRunId;
        this.pausedAgentId = pausedAgentId;
        this.requiredAction = requiredAction;
    }

    public String getPausedRunId() {
        return pausedRunId;
    }

    public String getPausedAgentId() {
        return pausedAgentId;
    }

    public RequiredAction getRequiredAction() {
        return requiredAction;
    }
}
