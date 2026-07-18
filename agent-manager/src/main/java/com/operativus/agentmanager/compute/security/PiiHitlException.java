package com.operativus.agentmanager.compute.security;

/**
 * Domain Responsibility: Thrown when the PII enforcement layer detects unscrubbed PII being
 * transmitted to a low-trust environment and the agent's policy mandates Human-in-the-Loop
 * (HITL) approval rather than silent redaction.
 *
 * <p>This exception is designed to be caught by the agent orchestration layer, which will
 * suspend the current Virtual Thread (yielding it at near-zero cost under Project Loom),
 * create a Pending Approval record in the database, and wait for an administrator to
 * approve or reject the transmission via the UI.</p>
 *
 * State: Stateless (Immutable Exception)
 */
public class PiiHitlException extends RuntimeException {

    private final String agentId;
    private final String policyName;
    private final String sessionId;

    public PiiHitlException(String agentId, String policyName, String sessionId) {
        super("PII HITL: Agent '" + agentId + "' triggered policy '" + policyName
                + "' — awaiting human approval in session '" + sessionId + "'");
        this.agentId = agentId;
        this.policyName = policyName;
        this.sessionId = sessionId;
    }

    public String getAgentId() {
        return agentId;
    }

    public String getPolicyName() {
        return policyName;
    }

    public String getSessionId() {
        return sessionId;
    }
}
