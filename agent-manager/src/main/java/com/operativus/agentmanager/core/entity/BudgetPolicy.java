package com.operativus.agentmanager.core.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Domain Responsibility: Persists an operator-configured budget ceiling that caps cumulative
 *     LLM spend per org (org-wide) or per specific agent (agent-scoped). The enforcement path
 *     reads this row at run start and binds {@code control.security.AgentContextHolder.CONTEXT}
 *     so {@code GenAiMetricsAdvisor.resolveBudgetCeiling()} can apply the ceiling mid-flight.
 * State: Stateful (JPA entity backed by {@code budget_policies} table).
 */
@Entity
@Table(name = "budget_policies")
public class BudgetPolicy {

    @Id
    private String id;

    @Column(name = "org_id", nullable = false)
    private String orgId;

    @Column(name = "agent_id")
    private String agentId;

    @Column(name = "ceiling_usd", nullable = false)
    private BigDecimal ceilingUsd;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getOrgId() { return orgId; }
    public void setOrgId(String orgId) { this.orgId = orgId; }

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public BigDecimal getCeilingUsd() { return ceilingUsd; }
    public void setCeilingUsd(BigDecimal ceilingUsd) { this.ceilingUsd = ceilingUsd; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
