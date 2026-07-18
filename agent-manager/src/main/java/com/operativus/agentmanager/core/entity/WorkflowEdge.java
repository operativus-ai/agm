package com.operativus.agentmanager.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Domain Responsibility: REQ-DR-5 — directed edge between two {@link WorkflowStep}s in
 *     a workflow's DAG. Replaces the implicit linear ordering of {@code step_order} with
 *     explicit (from, to, condition) edges. The dispatcher (PR-2) walks the graph via
 *     these edges; this PR ships the schema + entity + repository + validator only.
 *
 *     <p><b>condition</b> is nullable:
 *     <ul>
 *       <li>{@code null} — unconditional next-step (sequential continuation).</li>
 *       <li>{@code "true"}/{@code "false"}/{@code "else"} — CONDITION step branches.</li>
 *       <li>Arbitrary branch key — ROUTER step branches (e.g. {@code "approved"}).</li>
 *     </ul>
 *
 *     <p>Existing flat-list workflows have NO edges; the migration PR (REQ-DR-5 PR-5)
 *     inserts implicit sequential edges so they keep dispatching unchanged once the
 *     DAG walker lands.
 *
 * State: Stateful (data carrier / JPA entity).
 */
@Entity
@Table(name = "workflow_edges")
public class WorkflowEdge {

    @Id
    private String id;

    @Column(name = "workflow_id", nullable = false)
    private String workflowId;

    @Column(name = "from_step_id", nullable = false)
    private String fromStepId;

    @Column(name = "to_step_id", nullable = false)
    private String toStepId;

    @Column(name = "condition")
    private String condition;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public WorkflowEdge() {}

    public WorkflowEdge(String id, String workflowId, String fromStepId, String toStepId, String condition) {
        this.id = id;
        this.workflowId = workflowId;
        this.fromStepId = fromStepId;
        this.toStepId = toStepId;
        this.condition = condition;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getWorkflowId() { return workflowId; }
    public void setWorkflowId(String workflowId) { this.workflowId = workflowId; }

    public String getFromStepId() { return fromStepId; }
    public void setFromStepId(String fromStepId) { this.fromStepId = fromStepId; }

    public String getToStepId() { return toStepId; }
    public void setToStepId(String toStepId) { this.toStepId = toStepId; }

    public String getCondition() { return condition; }
    public void setCondition(String condition) { this.condition = condition; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}
