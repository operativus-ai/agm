package ai.operativus.agentmanager.compute.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.EntityListeners;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain Responsibility: Records each PII scrubbing event for NHI (Non-Human Identity) audit traceability.
 * Captures which agent triggered the scrub, which policy matched, and how many occurrences were redacted,
 * without ever storing the PII itself.
 * State: Stateful (Data Carrier / JPA Entity)
 */
@Entity
@Table(name = "pii_audit_log")
@EntityListeners(AuditingEntityListener.class)
public class PiiAuditLogEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "agent_id")
    private String agentId;

    @Column(name = "policy_name", nullable = false, length = 100)
    private String policyName;

    @Column(name = "scrub_strategy", nullable = false, length = 50)
    private String scrubStrategy;

    @Column(name = "occurrences", nullable = false)
    private int occurrences;

    @Column(name = "session_id")
    private String sessionId;

    /** Tenant scope (changeset 112). Stamped at write time from the agent-run context so the
     *  audit log can be served through a tenant-filtered endpoint. */
    @Column(name = "org_id")
    private String orgId;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    public PiiAuditLogEntity() {
    }

    public PiiAuditLogEntity(UUID id, String agentId, String policyName, String scrubStrategy,
                             int occurrences, String sessionId, String orgId) {
        this.id = id;
        this.agentId = agentId;
        this.policyName = policyName;
        this.scrubStrategy = scrubStrategy;
        this.occurrences = occurrences;
        this.sessionId = sessionId;
        this.orgId = orgId;
    }

    // --- Getters and Setters ---

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getPolicyName() {
        return policyName;
    }

    public void setPolicyName(String policyName) {
        this.policyName = policyName;
    }

    public String getScrubStrategy() {
        return scrubStrategy;
    }

    public void setScrubStrategy(String scrubStrategy) {
        this.scrubStrategy = scrubStrategy;
    }

    public int getOccurrences() {
        return occurrences;
    }

    public void setOccurrences(int occurrences) {
        this.occurrences = occurrences;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getOrgId() {
        return orgId;
    }

    public void setOrgId(String orgId) {
        this.orgId = orgId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PiiAuditLogEntity that = (PiiAuditLogEntity) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
