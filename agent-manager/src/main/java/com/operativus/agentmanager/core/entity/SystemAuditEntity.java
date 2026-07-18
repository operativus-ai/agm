package com.operativus.agentmanager.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Domain Responsibility: Generalized cross-cutting audit row for HTTP mutations on non-agent
 *   resources (user admin, models, knowledge bases, teams, schedules, workflows, evaluations,
 *   approvals, …) and authentication events (LOGIN_SUCCESS, LOGIN_FAILURE, LOGOUT). Carries an
 *   {@code org_id} column so listings can be tenant-scoped — the existing {@link AgentAuditEntity}
 *   has no {@code org_id} and is bound to {@code agent_id NOT NULL}, which cannot represent
 *   non-agent subjects or auth events.
 * State: Stateful (JPA Entity)
 */
@Entity
@Table(name = "system_audits")
public class SystemAuditEntity {

    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "org_id")
    private String orgId;

    @Column(name = "username")
    private String username;

    @Column(name = "action", nullable = false)
    private String action;

    @Column(name = "resource_type", nullable = false)
    private String resourceType;

    @Column(name = "resource_id")
    private String resourceId;

    @Column(name = "http_method")
    private String httpMethod;

    @Column(name = "request_path")
    private String requestPath;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public SystemAuditEntity() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getOrgId() { return orgId; }
    public void setOrgId(String orgId) { this.orgId = orgId; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }

    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }

    public String getHttpMethod() { return httpMethod; }
    public void setHttpMethod(String httpMethod) { this.httpMethod = httpMethod; }

    public String getRequestPath() { return requestPath; }
    public void setRequestPath(String requestPath) { this.requestPath = requestPath; }

    public Integer getResponseStatus() { return responseStatus; }
    public void setResponseStatus(Integer responseStatus) { this.responseStatus = responseStatus; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
