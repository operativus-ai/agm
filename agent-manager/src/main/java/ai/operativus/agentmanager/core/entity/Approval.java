package ai.operativus.agentmanager.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import ai.operativus.agentmanager.core.model.enums.RunStatus;

/**
 * Domain Responsibility: Represents the database schema and domain model for Approval (tracking hitting-the-loop manual authorization requests for agent actions).
 * State: Stateful (Data Carrier / JPA Entity)
 */
@Entity
@Table(name = "approvals")
public class Approval {

    @Id
    private String id;

    @Column(name = "org_id")
    private String orgId;

    @Column(name = "run_id")
    private String runId;

    @Column(name = "workflow_run_id")
    private String workflowRunId;

    @Column(name = "session_id", nullable = false)
    private String sessionId;

    @Column(name = "agent_id", nullable = false)
    private String agentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private RunStatus status;

    @Column(name = "tool_name", nullable = false)
    private String toolName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_arguments", columnDefinition = "jsonb")
    private String toolArguments;

    /**
     * SHA-256 (hex, 64 chars) over {@code toolName + ":" + toolArguments} captured at
     * creation. {@link ai.operativus.agentmanager.control.service.ApprovalService}
     * re-computes the hash on resolve and rejects the transition if it differs from
     * the stored value — defends against direct-DB / malicious-admin tampering with
     * what the human is approving. Nullable for pre-changeset-061 rows; verify is
     * skipped when null.
     */
    @Column(name = "payload_hash", length = 64)
    private String payloadHash;

    /**
     * T036(4) — number of distinct APPROVED votes required before the row transitions
     * from PENDING to APPROVED. Default 1 preserves the historical single-approver
     * behaviour. Operator sets a higher value (e.g. 2 for SOX dual-control) directly
     * on the row at creation; HITL upstream wiring is intentionally out-of-scope here.
     */
    @Column(name = "approvers_required", nullable = false)
    private int approversRequired = 1;

    /**
     * T036(4) — list of resolver IDs (mirrors {@code resolved_by}) that have already
     * voted APPROVED on this row. {@code ApprovalService.resolveApprovalForOrg}
     * dedupes incoming approvers against this list (one user, one vote) and flips
     * status to APPROVED when {@code approvedBy.size() >= approversRequired}.
     * Stored as a JSONB array; Hibernate uses Jackson under the hood for marshalling.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "approved_by", columnDefinition = "jsonb", nullable = false)
    private List<String> approvedBy = new ArrayList<>();

    @Column(name = "requested_by")
    private String requestedBy;

    @Column(name = "resolved_by")
    private String resolvedBy;

    @Column(name = "contextual_message")
    private String contextualMessage;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "decision_tier", length = 30)
    private String decisionTier;

    @Column(name = "reasoning_trace", columnDefinition = "text")
    private String reasoningTrace;

    @Column(name = "impact_assessment", columnDefinition = "text")
    private String impactAssessment;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @jakarta.persistence.Version
    @Column(name = "version", nullable = false)
    private Integer version = 0;

    public Approval() {
    }

    public Approval(String id, String runId, String workflowRunId, String sessionId, String agentId, RunStatus status, String toolName, String toolArguments, String requestedBy, String contextualMessage, String decisionTier, String reasoningTrace, String impactAssessment) {
        this.id = id;
        this.runId = runId;
        this.workflowRunId = workflowRunId;
        this.sessionId = sessionId;
        this.agentId = agentId;
        this.status = status;
        this.toolName = toolName;
        this.toolArguments = toolArguments;
        this.requestedBy = requestedBy;
        this.contextualMessage = contextualMessage;
        this.decisionTier = decisionTier;
        this.reasoningTrace = reasoningTrace;
        this.impactAssessment = impactAssessment;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getWorkflowRunId() {
        return workflowRunId;
    }

    public void setWorkflowRunId(String workflowRunId) {
        this.workflowRunId = workflowRunId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public RunStatus getStatus() {
        return status;
    }

    public void setStatus(RunStatus status) {
        this.status = status;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getToolArguments() {
        return toolArguments;
    }

    public void setToolArguments(String toolArguments) {
        this.toolArguments = toolArguments;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public void setRequestedBy(String requestedBy) {
        this.requestedBy = requestedBy;
    }

    public String getResolvedBy() {
        return resolvedBy;
    }

    public void setResolvedBy(String resolvedBy) {
        this.resolvedBy = resolvedBy;
    }

    public String getContextualMessage() {
        return contextualMessage;
    }

    public void setContextualMessage(String contextualMessage) {
        this.contextualMessage = contextualMessage;
    }

    public LocalDateTime getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(LocalDateTime resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public String getDecisionTier() {
        return decisionTier;
    }

    public void setDecisionTier(String decisionTier) {
        this.decisionTier = decisionTier;
    }

    public String getReasoningTrace() {
        return reasoningTrace;
    }

    public void setReasoningTrace(String reasoningTrace) {
        this.reasoningTrace = reasoningTrace;
    }

    public String getImpactAssessment() {
        return impactAssessment;
    }

    public void setImpactAssessment(String impactAssessment) {
        this.impactAssessment = impactAssessment;
    }

    public String getOrgId() {
        return orgId;
    }

    public void setOrgId(String orgId) {
        this.orgId = orgId;
    }

    public String getPayloadHash() {
        return payloadHash;
    }

    public void setPayloadHash(String payloadHash) {
        this.payloadHash = payloadHash;
    }

    public int getApproversRequired() {
        return approversRequired;
    }

    public void setApproversRequired(int approversRequired) {
        this.approversRequired = approversRequired;
    }

    public List<String> getApprovedBy() {
        return approvedBy;
    }

    public void setApprovedBy(List<String> approvedBy) {
        this.approvedBy = approvedBy;
    }
}
