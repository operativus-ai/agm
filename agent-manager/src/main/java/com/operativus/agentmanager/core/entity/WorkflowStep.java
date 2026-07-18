package com.operativus.agentmanager.core.entity;

import com.operativus.agentmanager.core.model.HumanReview;
import com.operativus.agentmanager.core.model.RouterStepConfig;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * Domain Responsibility: Represents the database schema and domain model for WorkflowStep (defining an individual operational action and assigned agent within a larger workflow).
 * State: Stateful (Data Carrier / JPA Entity)
 */
@Entity
@Table(name = "workflow_steps")
public class WorkflowStep {

    @Id
    private String id;

    @Column(name = "workflow_id", nullable = false)
    private String workflowId;

    @Column(name = "step_order", nullable = false)
    private Integer stepOrder;

    @Column(name = "agent_id")
    private String agentId;

    @Column(columnDefinition = "TEXT")
    private String action;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "router_config", columnDefinition = "jsonb")
    private RouterStepConfig routerConfig;

    @Column(name = "on_reject", length = 16)
    private String onReject;

    @Column(name = "else_step_id", length = 255)
    private String elseStepId;

    @Column(name = "requires_confirmation", nullable = false)
    private boolean requiresConfirmation;

    /**
     * REQ-HR-1 — unified HumanReview config. Soft-deprecates the
     * {@link #requiresConfirmation}, {@link #onReject}, and {@link #elseStepId}
     * columns above; both are readable during the REQ-HR-3..6 migration window.
     * Dispatcher integration lands in REQ-HR-3.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "human_review", columnDefinition = "jsonb")
    private HumanReview humanReview;

    /**
     * Per-node resilience config (DAG engine, changeset 109). All nullable — null = inherit the
     * global default ({@code agm.workflow.dag.default-node-*}), which itself defaults to "no retry,
     * no timeout" so legacy steps are unchanged.
     * <ul>
     *   <li>{@code retryMaxAttempts} — total attempts for a node whose executor returns failure
     *       (paused/stop/success are never retried). 1 = no retry.</li>
     *   <li>{@code retryBackoffMs} — fixed delay between retry attempts.</li>
     *   <li>{@code timeoutMs} — per-attempt wall-clock budget; exceeding it is a (retryable) failure.
     *       0/null = unbounded.</li>
     * </ul>
     */
    @Column(name = "retry_max_attempts")
    private Integer retryMaxAttempts;

    @Column(name = "retry_backoff_ms")
    private Long retryBackoffMs;

    @Column(name = "timeout_ms")
    private Long timeoutMs;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public WorkflowStep() {
    }

    public WorkflowStep(String id, String workflowId, Integer stepOrder, String agentId, String action) {
        this(id, workflowId, stepOrder, agentId, action, null, null);
    }

    public WorkflowStep(String id, String workflowId, Integer stepOrder, String agentId, String action,
                        RouterStepConfig routerConfig) {
        this(id, workflowId, stepOrder, agentId, action, routerConfig, null, null);
    }

    public WorkflowStep(String id, String workflowId, Integer stepOrder, String agentId, String action,
                        RouterStepConfig routerConfig, String onReject) {
        this(id, workflowId, stepOrder, agentId, action, routerConfig, onReject, null);
    }

    public WorkflowStep(String id, String workflowId, Integer stepOrder, String agentId, String action,
                        RouterStepConfig routerConfig, String onReject, String elseStepId) {
        this(id, workflowId, stepOrder, agentId, action, routerConfig, onReject, elseStepId, false);
    }

    public WorkflowStep(String id, String workflowId, Integer stepOrder, String agentId, String action,
                        RouterStepConfig routerConfig, String onReject, String elseStepId,
                        boolean requiresConfirmation) {
        this.id = id;
        this.workflowId = workflowId;
        this.stepOrder = stepOrder;
        this.agentId = agentId;
        this.action = action;
        this.routerConfig = routerConfig;
        this.onReject = onReject;
        this.elseStepId = elseStepId;
        this.requiresConfirmation = requiresConfirmation;
    }

    public Integer getRetryMaxAttempts() {
        return retryMaxAttempts;
    }

    public void setRetryMaxAttempts(Integer retryMaxAttempts) {
        this.retryMaxAttempts = retryMaxAttempts;
    }

    public Long getRetryBackoffMs() {
        return retryBackoffMs;
    }

    public void setRetryBackoffMs(Long retryBackoffMs) {
        this.retryBackoffMs = retryBackoffMs;
    }

    public Long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(Long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(String workflowId) {
        this.workflowId = workflowId;
    }

    public Integer getStepOrder() {
        return stepOrder;
    }

    public void setStepOrder(Integer stepOrder) {
        this.stepOrder = stepOrder;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public RouterStepConfig getRouterConfig() {
        return routerConfig;
    }

    public void setRouterConfig(RouterStepConfig routerConfig) {
        this.routerConfig = routerConfig;
    }

    public String getOnReject() {
        return onReject;
    }

    public void setOnReject(String onReject) {
        this.onReject = onReject;
    }

    public String getElseStepId() {
        return elseStepId;
    }

    public void setElseStepId(String elseStepId) {
        this.elseStepId = elseStepId;
    }

    public boolean isRequiresConfirmation() {
        return requiresConfirmation;
    }

    public void setRequiresConfirmation(boolean requiresConfirmation) {
        this.requiresConfirmation = requiresConfirmation;
    }

    public HumanReview getHumanReview() {
        return humanReview;
    }

    public void setHumanReview(HumanReview humanReview) {
        this.humanReview = humanReview;
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
}
