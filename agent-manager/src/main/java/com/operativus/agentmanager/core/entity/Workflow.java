package com.operativus.agentmanager.core.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Domain Responsibility: Represents the database schema and domain model for Workflow (defining a multi-step orchestration playbook for agents).
 * State: Stateful (Data Carrier / JPA Entity)
 */
@Entity
@Table(name = "workflows")
public class Workflow {

    @Id
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * Tenant identifier. Server-derived from {@code AgentContextHolder.getOrgId()} on
     * create paths; ignored from request-body deserialization via
     * {@link JsonProperty.Access#READ_ONLY}. Read on every controller mutation/read path
     * to enforce cross-tenant 404 (existence-leak protection). Child entities
     * ({@code WorkflowStep}, {@code WorkflowRun}) are tenant-scoped via parent traversal
     * — they do not carry their own {@code orgId}.
     */
    @Column(name = "org_id")
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String orgId;

    public Workflow() {
    }

    public Workflow(String id, String name, String description) {
        this.id = id;
        this.name = name;
        this.description = description;
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

    public String getOrgId() { return orgId; }
    public void setOrgId(String orgId) { this.orgId = orgId; }
}
