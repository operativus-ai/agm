package com.operativus.agentmanager.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Domain Responsibility: REQ-DR-5 — persisted manual canvas position of one step node in the
 *     DAG graph editor. One row per (workflowId, nodeId); {@code nodeId} is the
 *     {@link WorkflowStep} id. When a workflow has saved layout rows the editor renders the
 *     dragged positions instead of re-running ELK auto-layout; absence of rows = no saved
 *     layout (ELK fallback). Bare ids (no FK) per the repo-wide convention; tenancy flows from
 *     the parent workflow.
 *
 * State: Stateful (data carrier / JPA entity).
 */
@Entity
@Table(name = "workflow_node_layouts")
public class WorkflowNodeLayout {

    @Id
    private String id;

    @Column(name = "workflow_id", nullable = false)
    private String workflowId;

    @Column(name = "node_id", nullable = false)
    private String nodeId;

    @Column(name = "pos_x", nullable = false)
    private double posX;

    @Column(name = "pos_y", nullable = false)
    private double posY;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public WorkflowNodeLayout() {}

    public WorkflowNodeLayout(String id, String workflowId, String nodeId, double posX, double posY) {
        this.id = id;
        this.workflowId = workflowId;
        this.nodeId = nodeId;
        this.posX = posX;
        this.posY = posY;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getWorkflowId() { return workflowId; }
    public void setWorkflowId(String workflowId) { this.workflowId = workflowId; }

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }

    public double getPosX() { return posX; }
    public void setPosX(double posX) { this.posX = posX; }

    public double getPosY() { return posY; }
    public void setPosY(double posY) { this.posY = posY; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
