package com.operativus.agentmanager.core.event;

import org.springframework.context.ApplicationEvent;

/**
 * Domain Responsibility: Signals that an orchestration strategy handed off execution from one agent to another.
 *     Published by {@code RouterOrchestrator} after target resolution and by {@code SwarmOrchestrator} for each
 *     branch dispatch. Consumers (streaming-UI bridge, analytics) subscribe via Spring's async event multicaster.
 * State: Immutable value carrier.
 */
public class AgentSwitchEvent extends ApplicationEvent {

    private final String runId;
    private final String fromAgent;
    private final String toAgent;
    private final String strategy;
    private final String rationale;
    private final Integer depth;

    public AgentSwitchEvent(Object source, String runId, String fromAgent, String toAgent,
                            String strategy, String rationale, Integer depth) {
        super(source);
        this.runId = runId;
        this.fromAgent = fromAgent;
        this.toAgent = toAgent;
        this.strategy = strategy;
        this.rationale = rationale;
        this.depth = depth;
    }

    public String getRunId() { return runId; }
    public String getFromAgent() { return fromAgent; }
    public String getToAgent() { return toAgent; }
    public String getStrategy() { return strategy; }
    public String getRationale() { return rationale; }
    public Integer getDepth() { return depth; }
}
