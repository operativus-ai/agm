package ai.operativus.agentmanager.core.exception;

/**
 * Domain Responsibility: Thrown to suspend Agent execution when a restricted tool requires human approval. Caught by the service layer to return a PAUSED status.
 * State: Stateless (Exception carrier)
 */
public class ApprovalRequiredException extends RuntimeException {

    private final String approvalId;
    private final String toolName;
    private final String toolArgs;

    public ApprovalRequiredException(String approvalId, String toolName, String toolArgs) {
        super("Execution paused: Approval required for tool '" + toolName + "'. Approval ID: " + approvalId);
        this.approvalId = approvalId;
        this.toolName = toolName;
        this.toolArgs = toolArgs;
    }

    public String getApprovalId() {
        return approvalId;
    }

    public String getToolName() {
        return toolName;
    }

    public String getToolArgs() {
        return toolArgs;
    }
}
