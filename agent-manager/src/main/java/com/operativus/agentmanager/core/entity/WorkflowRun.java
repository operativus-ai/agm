package com.operativus.agentmanager.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import com.operativus.agentmanager.core.model.enums.RunStatus;
import com.operativus.agentmanager.core.model.workflow.DagFrontier;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;

/**
 * Domain Responsibility: Represents a single execution instance of a multi-agent chronological workflow.
 * State: Stateful (Data Carrier / JPA Entity)
 */
@Entity
@Table(name = "workflow_runs")
public class WorkflowRun {

    @Id
    private String id;

    @Column(name = "workflow_id", nullable = false)
    private String workflowId;

    @Column(name = "session_id", nullable = false)
    private String sessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunStatus status;

    @Column(name = "current_step_order", nullable = false)
    private Integer currentStepOrder;

    @Column(name = "current_payload", columnDefinition = "TEXT")
    private String currentPayload;

    @Column(name = "org_id", nullable = false)
    private String orgId;

    /**
     * DAG-3c frontier-aware resume snapshot. Non-null only while a DAG run is paused at a HITL gate;
     * the flat engine and completed/failed runs leave it null. JSONB mapping mirrors
     * {@code WorkflowStep.routerConfig}.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "dag_frontier", columnDefinition = "jsonb")
    private DagFrontier dagFrontier;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public WorkflowRun() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public WorkflowRun(String id, String workflowId, String sessionId, RunStatus status, Integer currentStepOrder, String currentPayload, String orgId) {
        this.id = id;
        this.workflowId = workflowId;
        this.sessionId = sessionId;
        this.status = status;
        this.currentStepOrder = currentStepOrder;
        this.currentPayload = currentPayload;
        this.orgId = orgId;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getWorkflowId() { return workflowId; }
    public void setWorkflowId(String workflowId) { this.workflowId = workflowId; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public RunStatus getStatus() { return status; }
    public void setStatus(RunStatus status) { this.status = status; }

    public Integer getCurrentStepOrder() { return currentStepOrder; }
    public void setCurrentStepOrder(Integer currentStepOrder) { this.currentStepOrder = currentStepOrder; }

    public String getCurrentPayload() { return currentPayload; }
    public void setCurrentPayload(String currentPayload) { this.currentPayload = currentPayload; }

    public String getOrgId() { return orgId; }
    public void setOrgId(String orgId) { this.orgId = orgId; }

    public DagFrontier getDagFrontier() { return dagFrontier; }
    public void setDagFrontier(DagFrontier dagFrontier) { this.dagFrontier = dagFrontier; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
