package ai.operativus.agentmanager.control.finops.exception;

/**
 * Domain Responsibility: Signals mid-flight FinOps budget exhaustion during active agent inference.
 * Thrown by {@code GenAiMetricsAdvisor} when the cumulative USD cost of an agent's inference stream
 * surpasses the configured budget ceiling, immediately interrupting the execution context.
 *
 * This exception transitions the executing agent into the ApprovalRepository HITL pause state
 * via {@code HitlPauseHandler}, ensuring zero token leakage beyond the authorized ceiling.
 *
 * State: Immutable (RuntimeException)
 */
public class FinOpsBudgetExhaustedException extends RuntimeException {

    private final String sessionId;
    private final String agentId;
    private final String runId;
    private final String modelId;
    private final double cumulativeUsd;
    private final double budgetCeilingUsd;

    public FinOpsBudgetExhaustedException(
            String sessionId,
            String agentId,
            String runId,
            String modelId,
            double cumulativeUsd,
            double budgetCeilingUsd) {
        super(String.format(
            "FinOps budget exhausted for agent [%s] session [%s]: cumulative $%.4f exceeds ceiling $%.4f on model [%s]",
            agentId, sessionId, cumulativeUsd, budgetCeilingUsd, modelId));
        this.sessionId = sessionId;
        this.agentId = agentId;
        this.runId = runId;
        this.modelId = modelId;
        this.cumulativeUsd = cumulativeUsd;
        this.budgetCeilingUsd = budgetCeilingUsd;
    }

    public String getSessionId()        { return sessionId; }
    public String getAgentId()          { return agentId; }
    public String getRunId()            { return runId; }
    public String getModelId()          { return modelId; }
    public double getCumulativeUsd()    { return cumulativeUsd; }
    public double getBudgetCeilingUsd() { return budgetCeilingUsd; }
}
