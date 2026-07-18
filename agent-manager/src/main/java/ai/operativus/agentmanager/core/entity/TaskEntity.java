package ai.operativus.agentmanager.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Domain Responsibility: One row per task in a {@code TeamMode.tasks} run. Owned by a
 *     parent team-run ({@code team_run_id} → {@code agent_runs.id}, CASCADE delete).
 *     Lifecycle managed by {@code TasksOrchestrator} via the {@link TaskStatus}
 *     enum; transitions enforced by the worker loop, not the DB.
 *
 *     <p><b>Dispatch idempotency (D5):</b> the worker loop uses an atomic status CAS:
 *     {@code UPDATE tasks SET status='IN_PROGRESS', dispatched_at=now(), worker_id=?
 *     WHERE id=? AND status='PENDING' RETURNING id}. {@code dispatched_at} is the
 *     observability column — it records when the CAS won, not the CAS check itself.
 *
 *     <p><b>Dependencies:</b> {@code dependencies} is a VARCHAR[] of task ids. A task
 *     with non-empty dependencies stays {@code PENDING} until every dep transitions to
 *     {@link TaskStatus#COMPLETED}. A dep transitioning to {@link TaskStatus#FAILED}
 *     marks the dependent {@link TaskStatus#BLOCKED} via the worker-loop failure policy
 *     (configurable per-team — see {@code agent.tasks.failure-policy}).
 *
 * State: Stateful (data carrier / JPA entity).
 */
@Entity
@Table(name = "tasks")
public class TaskEntity {

    @Id
    private String id;

    @Column(name = "team_run_id", nullable = false)
    private String teamRunId;

    @Column(name = "org_id", nullable = false)
    private String orgId;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "assignee_agent_id")
    private String assigneeAgentId;

    @Column(name = "parent_task_id")
    private String parentTaskId;

    @Column(name = "dependencies", columnDefinition = "VARCHAR(255)[]")
    private String[] dependencies;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private TaskStatus status;

    @Column(name = "result", columnDefinition = "TEXT")
    private String result;

    @Column(name = "worker_id")
    private String workerId;

    @Column(name = "dispatched_at")
    private LocalDateTime dispatchedAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public TaskEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTeamRunId() { return teamRunId; }
    public void setTeamRunId(String teamRunId) { this.teamRunId = teamRunId; }

    public String getOrgId() { return orgId; }
    public void setOrgId(String orgId) { this.orgId = orgId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getAssigneeAgentId() { return assigneeAgentId; }
    public void setAssigneeAgentId(String assigneeAgentId) { this.assigneeAgentId = assigneeAgentId; }

    public String getParentTaskId() { return parentTaskId; }
    public void setParentTaskId(String parentTaskId) { this.parentTaskId = parentTaskId; }

    public String[] getDependencies() { return dependencies; }
    public void setDependencies(String[] dependencies) { this.dependencies = dependencies; }

    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus status) { this.status = status; }

    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }

    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }

    public LocalDateTime getDispatchedAt() { return dispatchedAt; }
    public void setDispatchedAt(LocalDateTime dispatchedAt) { this.dispatchedAt = dispatchedAt; }

    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }

    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
