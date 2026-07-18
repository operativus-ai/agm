package ai.operativus.agentmanager.core.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Domain Responsibility: Represents the database schema and domain model for EvaluationSuite (grouping a collection of evaluation cases into a reusable test suite).
 * State: Stateful (Data Carrier / JPA Entity)
 */
@Entity
@Table(name = "evaluation_suites")
public class EvaluationSuite {

    @Id
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "created_by")
    private String createdBy;

    /**
     * Tenant identifier. Server-derived from {@code AgentContextHolder.getOrgId()} at
     * create time; never accepted from a request body. G2 — was missing entirely,
     * which let any caller list, fetch, and delete suites across org boundaries.
     */
    @Column(name = "org_id", nullable = false)
    private String orgId;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    public EvaluationSuite() {}

    public EvaluationSuite(String id, String name, String description, String createdBy) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.createdBy = createdBy;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public String getOrgId() { return orgId; }
    public void setOrgId(String orgId) { this.orgId = orgId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
