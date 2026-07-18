package ai.operativus.agentmanager.core.exception;

/**
 * Domain Responsibility: Thrown when a Swarm handoff or delegation attempts to transition from a lower
 * security tier to a higher security tier, requiring human approval before execution can continue.
 * Caught by AgentService to return a PAUSED RunResponse with SWARM_ESCALATION_APPROVAL metadata.
 * State: Stateless (Exception carrier)
 */
public class SwarmEscalationException extends RuntimeException {

    private final String sourceAgentId;
    private final String targetAgentId;
    private final int sourceTier;
    private final int targetTier;
    private final String escalationId;

    private String traceId;
    private String dagContext;

    public SwarmEscalationException(String sourceAgentId, String targetAgentId, int sourceTier, int targetTier) {
        super("Swarm escalation blocked: Agent '" + sourceAgentId + "' (Tier " + sourceTier
                + ") cannot hand off to Agent '" + targetAgentId + "' (Tier " + targetTier
                + ") without human approval.");
        this.sourceAgentId = sourceAgentId;
        this.targetAgentId = targetAgentId;
        this.sourceTier = sourceTier;
        this.targetTier = targetTier;
        this.escalationId = java.util.UUID.randomUUID().toString();
    }

    public String getSourceAgentId() {
        return sourceAgentId;
    }

    public String getTargetAgentId() {
        return targetAgentId;
    }

    public int getSourceTier() {
        return sourceTier;
    }

    public int getTargetTier() {
        return targetTier;
    }

    public String getEscalationId() {
        return escalationId;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getDagContext() {
        return dagContext;
    }

    public void setDagContext(String dagContext) {
        this.dagContext = dagContext;
    }
}
