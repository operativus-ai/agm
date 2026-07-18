package com.operativus.agentmanager.core.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Domain Responsibility: Represents the database schema and domain model for Schedule (configuring recurring chronological triggers for workflows or agents).
 * State: Stateful (Data Carrier / JPA Entity)
 */
@Entity
@Table(name = "schedules")
public class Schedule {

    @Id
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "cron_expression", nullable = false)
    private String cronExpression;

    @Column(name = "target_type", nullable = false, length = 50)
    private String targetType;

    @Column(name = "target_id", nullable = false)
    private String targetId;

    @Column(name = "resume_session_id")
    private String resumeSessionId;

    @Column(name = "contextual_prompt", columnDefinition = "TEXT")
    private String contextualPrompt;

    @Column(name = "is_active")
    private Boolean isActive;

    /**
     * When TRUE, {@code ScheduleExecutionPoller} flips {@code isActive} to FALSE on the
     * same transaction as the first dispatch — the schedule fires exactly once and never
     * re-fires. NOT NULL with DEFAULT FALSE in the schema (changeset 060) so legacy rows
     * keep their recurring semantics; the wrapper type is non-null at the JPA layer.
     */
    @Column(name = "one_shot", nullable = false)
    private boolean oneShot;

    @Column(name = "depends_on_schedule_id")
    private String dependsOnScheduleId; // DAG dependency — only run after this schedule completes

    @Column(name = "timezone", length = 50)
    private String timezone; // IANA timezone (e.g., "America/Chicago")

    /**
     * Tenant identifier. Server-derived from {@code AgentContextHolder.getOrgId()} on every
     * create path; ignored from request-body deserialization via
     * {@link JsonProperty.Access#READ_ONLY}. Read on every controller mutation/read path
     * to enforce cross-tenant 404 (existence-leak protection).
     */
    @Column(name = "org_id")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String orgId;

    /**
     * Optimistic-lock version. JPA increments on each save; concurrent PUTs through
     * {@code SchedulesController.updateSchedule} that race on the same row produce one
     * 200 (winner) and one {@code ObjectOptimisticLockingFailureException} → 409 via
     * {@code GlobalExceptionHandler}. Defaults to 0 for rows pre-existing the column add.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public Schedule() {
    }

    public Schedule(String id, String name, String description, String cronExpression, String targetType, String targetId, String resumeSessionId, String contextualPrompt, Boolean isActive) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.cronExpression = cronExpression;
        this.targetType = targetType;
        this.targetId = targetId;
        this.resumeSessionId = resumeSessionId;
        this.contextualPrompt = contextualPrompt;
        this.isActive = isActive;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getCronExpression() {
        return cronExpression;
    }

    public void setCronExpression(String cronExpression) {
        this.cronExpression = cronExpression;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public String getTargetId() {
        return targetId;
    }

    public void setTargetId(String targetId) {
        this.targetId = targetId;
    }

    public String getResumeSessionId() {
        return resumeSessionId;
    }

    public void setResumeSessionId(String resumeSessionId) {
        this.resumeSessionId = resumeSessionId;
    }

    public String getContextualPrompt() {
        return contextualPrompt;
    }

    public void setContextualPrompt(String contextualPrompt) {
        this.contextualPrompt = contextualPrompt;
    }

    public Boolean getActive() {
        return isActive;
    }

    public void setActive(Boolean active) {
        isActive = active;
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

    public String getDependsOnScheduleId() { return dependsOnScheduleId; }
    public void setDependsOnScheduleId(String dependsOnScheduleId) { this.dependsOnScheduleId = dependsOnScheduleId; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }

    public String getOrgId() { return orgId; }
    public void setOrgId(String orgId) { this.orgId = orgId; }

    public boolean isOneShot() { return oneShot; }
    public void setOneShot(boolean oneShot) { this.oneShot = oneShot; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
