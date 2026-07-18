package com.operativus.agentmanager.core.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Domain Responsibility: Represents the database schema and domain model for AgentAuditEntity (tracking agent lifecycle and configuration changes).
 * State: Stateful (Data Carrier / JPA Entity)
 *
 * <p><strong>Tenant scope (Fix C):</strong> {@link #orgId} is denormalized from
 * {@code agents.org_id} at write time. Nullable because rows whose parent agent has been
 * hard-deleted cannot derive an org and remain queryable for compliance retention. The
 * column is excluded from every tenant's listing query because
 * {@code AgentAuditRepository.search} predicates on {@code a.orgId = :orgId} where the
 * caller's org is never null (service-layer guard from Fix B / PR #289). Backed by
 * {@code idx_agent_audits_org_created} on {@code (org_id, created_at DESC)} per
 * Liquibase changeset 052.
 */
@Entity
@Table(name = "agent_audits")
public class AgentAuditEntity {

    @Id
    private String id;

    @Column(name = "agent_id", nullable = false)
    private String agentId;

    @Column(name = "org_id")
    private String orgId;

    @Column(name = "action", nullable = false)
    private String action; // CREATE, UPDATE, DELETE

    @Column(name = "username")
    private String username;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "changeset", columnDefinition = "jsonb")
    private String changeset;

    @Column(name = "version_number")
    private Integer versionNumber;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public AgentAuditEntity() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = LocalDateTime.now();
    }

    /**
     * @deprecated since Fix C — leaves {@code orgId} null; production callers must use
     *     {@link #AgentAuditEntity(String, String, String, String, String)} which accepts
     *     {@code orgId}. Retained for orphan-row test fixtures.
     */
    @Deprecated(since = "Fix C", forRemoval = false)
    public AgentAuditEntity(String agentId, String action, String username, String changeset) {
        this();
        this.agentId = agentId;
        this.action = action;
        this.username = username;
        this.changeset = changeset;
    }

    /**
     * @deprecated since Fix C — leaves {@code orgId} null; production callers must use
     *     {@link #AgentAuditEntity(String, String, String, String, String, Integer)} which
     *     accepts {@code orgId}. Retained for orphan-row test fixtures.
     */
    @Deprecated(since = "Fix C", forRemoval = false)
    public AgentAuditEntity(String agentId, String action, String username, String changeset, Integer versionNumber) {
        this(agentId, action, username, changeset);
        this.versionNumber = versionNumber;
    }

    public AgentAuditEntity(String agentId, String orgId, String action, String username, String changeset) {
        this();
        this.agentId = agentId;
        this.orgId = orgId;
        this.action = action;
        this.username = username;
        this.changeset = changeset;
    }

    public AgentAuditEntity(String agentId, String orgId, String action, String username, String changeset, Integer versionNumber) {
        this(agentId, orgId, action, username, changeset);
        this.versionNumber = versionNumber;
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getOrgId() { return orgId; }
    public void setOrgId(String orgId) { this.orgId = orgId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getChangeset() { return changeset; }
    public void setChangeset(String changeset) { this.changeset = changeset; }
    public Integer getVersionNumber() { return versionNumber; }
    public void setVersionNumber(Integer versionNumber) { this.versionNumber = versionNumber; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
