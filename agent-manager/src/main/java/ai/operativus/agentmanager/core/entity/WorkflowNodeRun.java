package ai.operativus.agentmanager.core.entity;

import ai.operativus.agentmanager.core.model.enums.NodeKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Domain Responsibility: One node execution within a DAG workflow run (REQ-DR-5, DAG-3a) —
 *     the persisted form of a {@code StepOutput}. Serves as the per-node trace, the fan-in
 *     source for downstream nodes, and (from DAG-3c) the resume state. Bare ids (no FK) per the
 *     repo-wide convention; tenancy flows from the parent {@link WorkflowRun}. Keyed logically by
 *     {@code (run_id, node_id, attempt)}.
 * State: Stateful (data carrier / JPA entity).
 */
@Entity
@Table(name = "workflow_node_runs")
public class WorkflowNodeRun {

    @Id
    private String id;

    @Column(name = "run_id", nullable = false)
    private String runId;

    @Column(name = "workflow_id", nullable = false)
    private String workflowId;

    @Column(name = "node_id", nullable = false)
    private String nodeId;

    @Column(name = "node_name")
    private String nodeName;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 32)
    private NodeKind kind;

    @Column(name = "attempt", nullable = false)
    private Integer attempt = 1;

    @Column(name = "content")
    private String content;

    @Column(name = "success", nullable = false)
    private boolean success = true;

    @Column(name = "error")
    private String error;

    @Column(name = "paused", nullable = false)
    private boolean paused = false;

    @Column(name = "pause_kind", length = 32)
    private String pauseKind;

    @Column(name = "token_cost")
    private Long tokenCost;

    @Column(name = "model_id")
    private String modelId;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public WorkflowNodeRun() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }

    public String getWorkflowId() { return workflowId; }
    public void setWorkflowId(String workflowId) { this.workflowId = workflowId; }

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }

    public String getNodeName() { return nodeName; }
    public void setNodeName(String nodeName) { this.nodeName = nodeName; }

    public NodeKind getKind() { return kind; }
    public void setKind(NodeKind kind) { this.kind = kind; }

    public Integer getAttempt() { return attempt; }
    public void setAttempt(Integer attempt) { this.attempt = attempt; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public boolean isPaused() { return paused; }
    public void setPaused(boolean paused) { this.paused = paused; }

    public String getPauseKind() { return pauseKind; }
    public void setPauseKind(String pauseKind) { this.pauseKind = pauseKind; }

    public Long getTokenCost() { return tokenCost; }
    public void setTokenCost(Long tokenCost) { this.tokenCost = tokenCost; }

    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
